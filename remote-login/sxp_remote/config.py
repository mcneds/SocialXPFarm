"""Local, owner-only configuration. Never accepts credentials through Discord."""
import getpass
import json
import os
from pathlib import Path
import secrets
import tempfile
import uuid

DEFAULT_PATH = Path.home() / '.config' / 'socialxpfarm-remote' / 'config.json'
DEFAULT_CLIENT = 'e16699bb-2aa8-46da-b5e3-45cbcce29091'


def private_write(path: Path, value: dict):
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Configuration must not be a symbolic link')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.sxp-')
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            json.dump(value, output, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def load(path: Path):
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Configuration must not be a symbolic link')
    if os.name != 'posix':
        raise ValueError('The companion currently requires Linux/POSIX file permissions')
    if path.stat().st_mode & 0o077 or path.parent.stat().st_mode & 0o077:
        raise ValueError('Restrict configuration directory/file to 700/600')
    config = json.loads(path.read_text())
    if not isinstance(config.get('botToken'), str) or not config['botToken'].strip():
        raise ValueError('Missing bot token')
    if not str(config.get('ownerId', '')).isdigit() or int(config['ownerId']) <= 0:
        raise ValueError('Invalid owner ID')
    if not isinstance(config.get('port'), int) or not 1024 <= config['port'] <= 65535:
        raise ValueError('Invalid local port')
    seen = set()
    for item in config['instances']:
        uuid.UUID(item['id'])
        if item['id'] in seen or not isinstance(item['secret'], str) or len(item['secret']) < 32:
            raise ValueError('Invalid or duplicate instance')
        if not isinstance(item['label'], str) or not 1 <= len(item['label']) <= 64:
            raise ValueError('Invalid instance label')
        seen.add(item['id'])
    return config


def setup(path: Path):
    if os.name != 'posix':
        raise ValueError('Run the companion on the Linux PC hosting the instances')
    config = load(path) if path.exists() else {
        'botToken': getpass.getpass('Discord bot token (hidden): ').strip(),
        'ownerId': input('Your Discord user ID: ').strip(), 'port': 38471, 'instances': []}
    print('Add instance game directories (.minecraft), one at a time. Blank finishes.')
    while raw := input('Game directory: ').strip():
        directory = Path(raw).expanduser().resolve(strict=True)
        if not (directory / 'mods').is_dir():
            print('Choose the game directory containing mods/ and config/.')
            continue
        existing = next((i for i in config['instances'] if i.get('gameDirectory') == str(directory)), None)
        label = input('Instance label: ').strip() or directory.parent.name
        item = existing or {'id': str(uuid.uuid4()), 'secret': secrets.token_urlsafe(32), 'gameDirectory': str(directory)}
        item['label'] = label[:64]
        if existing is None:
            config['instances'].append(item)
        private_write(directory / 'config' / 'socialxpfarm-auth' / 'remote.json', {
            'enabled': True, 'instanceId': item['id'], 'secret': item['secret'],
            'port': config['port'], 'clientId': DEFAULT_CLIENT})
    private_write(path, config)
    load(path)
    print('Configuration saved. Restart registered Minecraft instances, then start the companion.')
