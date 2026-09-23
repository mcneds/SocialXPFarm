"""Generate a systemd user unit. Starting/enabling it remains an explicit local command."""
from pathlib import Path
import sys


def quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'


def unit(config):
    directory = Path(__file__).resolve().parents[1]
    return '\n'.join([
        '[Unit]', 'Description=SocialXPFarm private Discord login companion', 'After=network-online.target', '',
        # WorkingDirectory takes a literal path, unlike ExecStart's quoted arguments.
        '[Service]', 'Type=simple', 'WorkingDirectory=' + str(directory).replace('%', '%%'),
        'ExecStart=' + quote(sys.executable) + ' -m sxp_remote serve --config ' + quote(config.resolve()),
        'Restart=on-failure', 'RestartSec=10', 'UMask=0077', 'NoNewPrivileges=true', '',
        '[Install]', 'WantedBy=default.target', ''])
