"""Explicit local setup for a user-owned OAuth registration and callback-only tunnel."""
import json
import os
from pathlib import Path
import shutil
import tempfile
import uuid
from urllib.parse import urlparse

from .config import DEFAULT_CLIENT, load, private_write, browser_callback
from .service import quote


def private_read(path):
    if path.is_symlink() or path.parent.is_symlink() or path.stat().st_mode & 0o077 or path.parent.stat().st_mode & 0o077:
        raise ValueError('Use an owner-only regular configuration file')
    return json.loads(path.read_text())


def configure(path, client_id, redirect_uri, port):
    config = load(path)
    settings = browser_callback({'clientId': client_id, 'redirectUri': redirect_uri, 'port': port}, config['port'])
    if config.get('browserCallback') and config['browserCallback'] != settings:
        raise ValueError('Existing callback configuration differs; roll back enabled instances before changing it locally')
    config['browserCallback'] = settings
    private_write(path, config)


def instance_config(path, reference, enable):
    config = load(path)
    from .core import Registry
    instance = Registry(config).resolve_instance(reference)
    item = next(i for i in config['instances'] if i['id'] == instance.id)
    directory = Path(item['gameDirectory']) / 'config/socialxpfarm-auth'
    file, backup = directory / 'remote.json', directory / 'remote-before-web.json'
    current = private_read(file)
    if current.get('instanceId') != item['id'] or current.get('secret') != item['secret']:
        raise ValueError('Instance registration changed; rerun setup')
    if enable:
        settings = config.get('browserCallback')
        if not settings:
            raise ValueError('Configure the callback service first')
        if not backup.exists() and not backup.is_symlink():
            private_write(backup, current)
        else:
            original = private_read(backup)
            if original.get('instanceId') != item['id'] or original.get('secret') != item['secret']:
                raise ValueError('Backup belongs to another registration')
        current.update(clientId=settings['clientId'], redirectUri=settings['redirectUri'])
    else:
        original = private_read(backup)
        if original.get('instanceId') != item['id'] or original.get('secret') != item['secret']:
            raise ValueError('Backup belongs to another registration')
        current.pop('redirectUri', None)
        if 'redirectUri' in original:
            current['redirectUri'] = original['redirectUri']
        current['clientId'] = original.get('clientId', DEFAULT_CLIENT)
    # Account credentials and every other instance are deliberately untouched.
    private_write(file, current)


def tunnel_config(config, tunnel_id, credential_path):
    settings = browser_callback(config.get('browserCallback'), config['port'])
    if not settings:
        raise ValueError('Configure the callback service first')
    tunnel_id = str(uuid.UUID(tunnel_id))
    host = urlparse(settings['redirectUri']).hostname
    # JSON-quoted strings are valid YAML. The final catch-all never forwards arbitrary routes.
    return '\n'.join([
        'tunnel: ' + tunnel_id,
        'credentials-file: ' + json.dumps(str(credential_path.resolve())),
        'loglevel: warn', 'ingress:',
        '  - hostname: ' + host,
        "    path: ^/oauth/(callback|result/[0-9a-f]{32}(/status)?)$",
        "    service: http://127.0.0.1:" + str(settings['port']),
        '  - service: http_status:404', '',
    ])


def tunnel_unit(config_path, executable=None):
    executable = executable or shutil.which('cloudflared')
    if not executable or not Path(executable).is_file() or not os.access(executable, os.X_OK):
        raise ValueError('Install cloudflared before generating its service')
    return '\n'.join([
        '[Unit]', 'Description=SocialXPFarm browser callback tunnel', 'After=network-online.target', '',
        '[Service]', 'Type=simple',
        'ExecStart=' + quote(Path(executable).absolute()) + ' --no-autoupdate --config ' + quote(config_path.resolve()) + ' tunnel run',
        'Restart=on-failure', 'RestartSec=10', 'UMask=0077', 'NoNewPrivileges=true', '',
        '[Install]', 'WantedBy=default.target', '',
    ])


def write_generated(path, text):
    # Generate/validate completely before replacing a file; failed setup cannot truncate a service unit.
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('Output must not be a symbolic link')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.sxp-', dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)
