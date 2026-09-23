import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from sxp_remote.config import DEFAULT_CLIENT, browser_callback, load, private_write, setup
from sxp_remote.web_setup import configure, instance_config, tunnel_config, tunnel_unit, write_generated
from test_callback_web import SETTINGS
from helpers import *


class WebSetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'companion/config.json'
        self.config = config()
        self.originals = {}
        for item in self.config['instances']:
            directory = self.root / item['label']
            item['gameDirectory'] = str(directory)
            (directory/'mods').mkdir(parents=True)
            remote = directory / 'config/socialxpfarm-auth/remote.json'
            original = {'enabled':True, 'instanceId':item['id'], 'secret':item['secret'], 'port':38471, 'clientId':DEFAULT_CLIENT}
            private_write(remote, original)
            private_write(remote.parent/'account.json', {'synthetic':'credential-unchanged'})
            self.originals[item['id']] = remote
        private_write(self.path, self.config)

    def test_unconfigured_load_keeps_legacy_behavior_and_enable_requires_setup(self):
        self.assertNotIn('browserCallback', load(self.path))
        before = self.originals[INSTANCE_A].read_bytes()
        with self.assertRaises(ValueError):
            instance_config(self.path, 'Alt A', True)
        self.assertEqual(before, self.originals[INSTANCE_A].read_bytes())

    def test_invalid_registration_endpoint_and_control_port_are_rejected(self):
        changes = [dict(clientId=DEFAULT_CLIENT), dict(clientId='bad'), dict(port=38471), dict(port=True),
                   dict(redirectUri='http://auth.example.test/oauth/callback'), dict(redirectUri='https://localhost/oauth/callback'),
                   dict(redirectUri='https://127.0.0.1/oauth/callback'), dict(redirectUri='https://auth.example.test:443/oauth/callback'),
                   dict(redirectUri='https://auth.example.test/oauth/callback?code=x'), dict(redirectUri='https://auth.example.test/elsewhere'),
                   dict(redirectUri='https://user@auth.example.test/oauth/callback')]
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                browser_callback(dict(SETTINGS, **change))

    def test_setup_enable_one_instance_repeat_and_rollback_preserve_credentials(self):
        credential = self.originals[INSTANCE_A].parent/'account.json'
        before = credential.read_bytes()
        other = self.originals[INSTANCE_B].read_bytes()
        configure(self.path, SETTINGS['clientId'], SETTINGS['redirectUri'], 38472)
        self.assertNotIn('redirectUri', json.loads(self.originals[INSTANCE_A].read_text()))
        for _ in range(2):
            instance_config(self.path, ' ALT A ', True)
        updated = json.loads(self.originals[INSTANCE_A].read_text())
        self.assertEqual(SETTINGS['clientId'], updated['clientId'])
        self.assertEqual(SETTINGS['redirectUri'], updated['redirectUri'])
        self.assertEqual(SECRET_A, updated['secret'])
        self.assertEqual(0, self.originals[INSTANCE_A].stat().st_mode & 0o077)
        self.assertEqual(other, self.originals[INSTANCE_B].read_bytes())
        self.assertEqual(before, credential.read_bytes())
        instance_config(self.path, 'Alt A', False)
        updated = json.loads(self.originals[INSTANCE_A].read_text())
        self.assertEqual(DEFAULT_CLIENT, updated['clientId'])
        self.assertNotIn('redirectUri', updated)
        self.assertEqual(before, credential.read_bytes())

    def test_rerunning_original_wizard_preserves_hosted_registration(self):
        configure(self.path, SETTINGS['clientId'], SETTINGS['redirectUri'], 38472)
        instance_config(self.path, INSTANCE_A, True)
        directory = self.config['instances'][0]['gameDirectory']
        with patch('builtins.input', side_effect=[directory, 'Renamed alt', '']), patch('builtins.print'):
            setup(self.path)
        remote = json.loads(self.originals[INSTANCE_A].read_text())
        self.assertEqual(SETTINGS['clientId'], remote['clientId'])
        self.assertEqual(SETTINGS['redirectUri'], remote['redirectUri'])
        self.assertEqual(SETTINGS, load(self.path)['browserCallback'])

    def test_symlink_and_foreign_backup_do_not_replace_instance(self):
        configure(self.path, SETTINGS['clientId'], SETTINGS['redirectUri'], 38472)
        remote = self.originals[INSTANCE_A]
        backup = remote.parent/'remote-before-web.json'
        backup.symlink_to(remote)
        with self.assertRaises(ValueError):
            instance_config(self.path, INSTANCE_A, True)
        backup.unlink()
        private_write(backup, {'instanceId':INSTANCE_B, 'secret':SECRET_B})
        with self.assertRaises(ValueError):
            instance_config(self.path, INSTANCE_A, True)
        self.assertNotIn('redirectUri', json.loads(remote.read_text()))

    def test_tunnel_config_exposes_only_callback_origin_and_no_credentials(self):
        configure(self.path, SETTINGS['clientId'], SETTINGS['redirectUri'], 38472)
        text = tunnel_config(load(self.path), INSTANCE_A, self.root/'cloudflare/credentials.json')
        self.assertIn('hostname: auth.example.test', text)
        self.assertIn('http://127.0.0.1:38472', text)
        self.assertNotIn('38471', text)
        self.assertIn('path: ^/oauth/', text)
        self.assertIn('service: http_status:404', text)
        self.assertNotIn(SECRET_A, text)
        self.assertNotIn('synthetic-bot-token', text)

    def test_generated_files_are_atomic_private_and_reject_symlinks(self):
        target = self.root/'unit.service'
        write_generated(target, 'original')
        self.assertEqual(0, target.stat().st_mode & 0o077)
        with patch('sxp_remote.web_setup.os.replace', side_effect=OSError('synthetic')):
            with self.assertRaises(OSError):
                write_generated(target, 'replacement')
        self.assertEqual('original', target.read_text())
        link = self.root/'link'
        link.symlink_to(target)
        with self.assertRaises(ValueError):
            write_generated(link, 'replacement')

    @unittest.skipUnless(shutil.which('systemd-analyze'), 'systemd-analyze required in CI')
    def test_tunnel_service_has_valid_quoting_and_restart_policy(self):
        text = tunnel_unit(self.root/'config with space %n.yml', Path(sys.executable))
        self.assertIn('Restart=on-failure', text)
        self.assertIn('%%n.yml', text)
        self.assertNotIn('token', text)
        target = self.root/'tunnel.service'
        write_generated(target, text)
        result = subprocess.run(['systemd-analyze','verify','--man=no',str(target)], capture_output=True,text=True,timeout=30)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
