import asyncio
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

from aiohttp import ClientSession
from aiohttp.test_utils import TestServer
from sxp_remote.app import Bot, Controls, Delivery, make_http, authorized
from sxp_remote.config import load, private_write, setup
from sxp_remote.core import Registry
from helpers import *


class LocalHttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.registry = Registry(config())
        self.server = TestServer(make_http(self.registry), host='127.0.0.1')
        await self.server.start_server()
        self.http = ClientSession()
        self.url = self.server.make_url(f'/v1/instances/{INSTANCE_A}/exchange')
        self.headers = {'Authorization': 'Bearer ' + SECRET_A}

    async def asyncTearDown(self):
        await self.http.close()
        await self.server.close()

    async def test_real_http_exchange_command_ack_and_per_instance_auth(self):
        async with self.http.post(self.url, json={'runId': RUN_A, 'snapshot': snapshot()}, headers=self.headers) as response:
            self.assertEqual(200, response.status)
            self.assertEqual('no-store', response.headers['Cache-Control'])
        command = self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        async with self.http.post(self.url, json={'runId': RUN_A}, headers=self.headers) as response:
            self.assertEqual(command, (await response.json())['command'])
        async with self.http.post(self.url, json={'runId': RUN_A, 'ack': command['id']}, headers=self.headers) as response:
            self.assertIsNone((await response.json())['command'])
        async with self.http.post(self.url, json={}, headers={'Authorization': 'Bearer ' + SECRET_B}) as response:
            self.assertEqual(401, response.status)

    async def test_browser_origin_rebinding_and_bad_payload_fail_closed(self):
        for additions in [{'Origin': 'https://evil.test'}, {'Host': 'evil.test'}]:
            async with self.http.post(self.url, json={}, headers=dict(self.headers, **additions)) as response:
                self.assertEqual(403, response.status)
        for payload in [{}, [], {'runId': RUN_A, 'secret': 'synthetic-secret'}, {'runId': RUN_A, 'snapshot': snapshot(device_code='private')}]:
            async with self.http.post(self.url, json=payload, headers=self.headers) as response:
                self.assertEqual(400, response.status)
                self.assertNotIn('private', await response.text())
        async with self.http.post(self.url, data='x'*20000, headers=self.headers) as response:
            self.assertEqual(413, response.status)

    async def test_no_public_control_or_get_login_endpoint(self):
        async with self.http.get(self.url) as response:
            self.assertEqual(405, response.status)
        async with self.http.post(self.server.make_url('/login'), json={}) as response:
            self.assertEqual(404, response.status)


class DiscordAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.registry = Registry(config())
        publish(self.registry)
        self.bot = Bot(self.registry)

    async def asyncTearDown(self):
        await self.bot.close()

    def interaction(self, owner=OWNER, guild=None):
        return SimpleNamespace(user=SimpleNamespace(id=owner), guild=guild,
            response=SimpleNamespace(send_message=AsyncMock(), defer=AsyncMock()),
            followup=SimpleNamespace(send=AsyncMock()))

    async def test_nonowner_and_guild_interactions_cannot_queue_commands(self):
        for interaction in [self.interaction(OWNER+1), self.interaction(guild=object())]:
            await self.bot.action(interaction, INSTANCE_A, CONTEXT_A, 'login')
            interaction.response.send_message.assert_awaited_once()
            interaction.response.defer.assert_not_awaited()
            self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_owner_button_defers_and_routes_only_selected_instance(self):
        interaction = self.interaction()
        await self.bot.action(interaction, INSTANCE_A, CONTEXT_A, 'login')
        interaction.response.defer.assert_awaited_once()
        self.assertEqual('login', self.registry.instances[INSTANCE_A].command['action'])
        self.assertIsNone(self.registry.instances[INSTANCE_B].command)
        interaction.followup.send.assert_awaited_once()

    async def test_idle_slash_login_reports_no_pending_recovery_without_queuing_work(self):
        publish(self.registry, value=snapshot('idle', context='', canLogin=False))
        interaction = self.interaction()
        await self.bot.named_action(interaction, INSTANCE_A, 'login')
        interaction.response.defer.assert_awaited_once()
        response = interaction.followup.send.call_args.args[0]
        self.assertIn('No authentication recovery is pending', response)
        self.assertNotIn('stale', response)
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_stale_button_reports_error_without_restarting_login(self):
        interaction = self.interaction()
        await self.bot.action(interaction, INSTANCE_A, str(uuid.uuid4()), 'login')
        self.assertIn('stale', interaction.followup.send.call_args.args[0])
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_views_fit_discord_limits_and_disable_invalid_actions(self):
        view = Controls(self.bot, INSTANCE_A, CONTEXT_A, login=False, cancel=True)
        self.assertTrue(view.is_persistent())
        self.assertTrue(view.children[0].disabled)
        self.assertFalse(view.children[1].disabled)
        self.assertTrue(all(len(b.custom_id) <= 100 for b in view.children))
        self.assertEqual(['sxp'], [c.name for c in self.bot.tree.get_commands()])

    async def test_render_never_echoes_arbitrary_provider_status_or_private_tokens(self):
        value = snapshot('signing_in', message='synthetic-private-token')
        publish(self.registry, value=value)
        prompt = {'userCode':'ABCD-EFGH', 'verificationUri':'https://microsoft.com/devicelogin', 'expiresAt':900000}
        text, view = Delivery(self.bot).render(self.registry.instances[INSTANCE_A], True, prompt)
        self.assertNotIn('synthetic-private-token', text)
        self.assertIn('ABCD-EFGH', text)
        self.assertIn('AltA', text)
        self.assertIsNotNone(view)


class LocalSetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / 'private' / 'config.json'

    def test_private_atomic_configuration_and_permission_checks(self):
        private_write(self.path, config())
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        self.assertEqual(0o700, self.path.parent.stat().st_mode & 0o777)
        self.assertEqual(config(), load(self.path))
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            load(self.path)

    def test_symlink_cannot_redirect_config_write(self):
        outside = self.root / 'outside'
        outside.write_text('unchanged')
        self.path.parent.mkdir()
        self.path.symlink_to(outside)
        with self.assertRaises(ValueError):
            private_write(self.path, config())
        self.assertEqual('unchanged', outside.read_text())

    def test_wizard_two_instances_get_distinct_secrets_without_microsoft_tokens(self):
        games = [self.root / 'one', self.root / 'two']
        for game in games:
            (game/'mods').mkdir(parents=True)
        inputs = [str(OWNER), str(games[0]), 'Alt A', str(games[1]), 'Alt B', '']
        with patch('builtins.input', side_effect=inputs), patch('getpass.getpass', return_value='synthetic-bot-token'), patch('builtins.print'):
            setup(self.path)
        value = load(self.path)
        self.assertEqual(2, len(value['instances']))
        self.assertNotEqual(value['instances'][0]['secret'], value['instances'][1]['secret'])
        for index, game in enumerate(games):
            remote = json.loads((game/'config/socialxpfarm-auth/remote.json').read_text())
            self.assertEqual(value['instances'][index]['id'], remote['instanceId'])
            self.assertEqual(value['instances'][index]['secret'], remote['secret'])
            self.assertNotIn('botToken', remote)
            self.assertNotIn('refreshToken', remote)

    def test_repeat_setup_keeps_instance_identity_and_secret(self):
        game = self.root/'game'
        (game/'mods').mkdir(parents=True)
        with patch('builtins.input', side_effect=[str(OWNER), str(game), 'Alt', '']), patch('getpass.getpass', return_value='synthetic-bot-token'), patch('builtins.print'):
            setup(self.path)
        old = load(self.path)['instances'][0].copy()
        with patch('builtins.input', side_effect=[str(game), 'Renamed', '']), patch('builtins.print'):
            setup(self.path)
        new = load(self.path)['instances'][0]
        self.assertEqual(old['secret'], new['secret'])
        self.assertEqual(old['id'], new['id'])
        self.assertEqual('Renamed', new['label'])

    def test_systemd_unit_uses_current_interpreter_and_never_contains_bot_secret(self):
        from sxp_remote.service import unit
        text = unit(self.path)
        self.assertIn('-m sxp_remote serve --config', text)
        self.assertIn('Restart=on-failure', text)
        self.assertIn('UMask=0077', text)
        self.assertNotIn('botToken', text)

    def test_packaging_omits_local_configuration_and_bytecode(self):
        import subprocess
        import sys
        import zipfile
        root = Path(__file__).resolve().parents[1]
        output = self.root/'companion.zip'
        subprocess.run([sys.executable, str(root/'package_release.py'), str(output)], check=True)
        with zipfile.ZipFile(output) as archive:
            names = archive.namelist()
            self.assertIn('remote-login/sxp_remote/app.py', names)
            self.assertIn('remote-login/requirements.lock', names)
            self.assertFalse(any('__pycache__' in n or '/.local/' in n for n in names))
            self.assertFalse(any(n.endswith('/config.json') or n.endswith('/state.json') for n in names))
