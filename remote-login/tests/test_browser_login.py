"""Synthetic Auth Me-style account picker and Discord callback tests; no external traffic."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
from urllib.parse import urlencode, urlparse

from sxp_remote.app import Bot, Controls, Delivery, CallbackModal
from sxp_remote.core import Registry, validate_snapshot, browser_parameters, validate_callback
from sxp_remote.notifications import Notifications
from helpers import *

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/browser-snapshot.json').read_text())


def prompt(state='S' * 43, port=43871, expires=300000):
    value = copy.deepcopy(FIXTURE['browserPrompt'])
    params = browser_parameters(value)
    params.update(state=state, redirect_uri=f'http://localhost:{port}/callback')
    value.update(authorizationUri=value['authorizationUri'].split('?')[0] + '?' + urlencode(params), expiresAt=expires)
    return value


def address(value=None, code='synthetic-secret'):
    params = browser_parameters(value or prompt())
    return params['redirect_uri'] + '?' + urlencode({'state': params['state'], 'code': code})


class BrowserProtocolTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.registry = Registry(config(), self.clock, self.clock)
        publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt()))

    def test_shared_fixture_and_safe_public_parameters(self):
        self.assertEqual(FIXTURE, validate_snapshot(FIXTURE, FIXTURE['runId']))
        params = browser_parameters(FIXTURE['browserPrompt'])
        self.assertEqual('select_account', params['prompt'])
        self.assertEqual('S256', params['code_challenge_method'])
        self.assertNotIn('code_verifier', params)

    def test_unsafe_or_mixed_browser_prompts_rejected(self):
        for change in [dict(prompt='none'), dict(redirect_uri='https://evil.test/callback'),
                       dict(redirect_uri='http://localhost:65536/callback'), dict(code_challenge_method='plain'),
                       dict(code_verifier='synthetic-secret'), dict(state=''), dict(client_id='invalid')]:
            p = prompt()
            params = browser_parameters(p)
            params.update(change)
            p['authorizationUri'] = p['authorizationUri'].split('?')[0] + '?' + urlencode(params)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_snapshot(snapshot('signing_in', browserPrompt=p), RUN_A)
        for p in [dict(prompt(), authorizationUri='https://evil.test/?code=synthetic-secret'),
                  dict(prompt(), expiresAt=True), dict(prompt(), verifier='synthetic-secret')]:
            with self.assertRaises(ValueError):
                browser_parameters(p)
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot('needs_login', browserPrompt=prompt()), RUN_A)
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot('signing_in', browserPrompt=prompt(), prompt={
                'userCode': 'TEST-CODE', 'verificationUri': 'https://microsoft.com/devicelogin', 'expiresAt':300000}), RUN_A)

    def test_malformed_foreign_and_duplicate_callbacks_never_queue_or_echo(self):
        good = address()
        for wrong in [good.replace('localhost', 'evil.test'), good.replace('localhost', '127.0.0.1'),
                      good.replace('43871', '43872'), good.replace('/callback?', '/callback/extra?'),
                      good.replace('http:', 'https:'), good + '#fragment', good + '&code=duplicate',
                      good + '&state=duplicate', good + '&error=denied',
                      address(prompt('T' * 43)), 'synthetic-secret', None, 'x'*4001,
                      good.replace('synthetic-secret', ''), good.replace('state=', 'state=%ZZ'), good.replace('synthetic-secret', '%ZZ'),
                      good.replace('synthetic-secret', 'raw space'),
                      good.replace('http:', 'h\nttp:')]:
            with self.subTest(wrong=wrong), self.assertRaises(ValueError) as error:
                self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', wrong)
            self.assertNotIn('synthetic-secret', str(error.exception))
            self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    def test_owner_context_lifecycle_and_cross_alt_isolation(self):
        publish(self.registry, INSTANCE_B, snapshot('signing_in', context=str(uuid.uuid4()), accountId=INSTANCE_B,
                                                  browserPrompt=prompt('T' * 43, 43872)))
        for owner, instance, context in [(OWNER+1, INSTANCE_A, CONTEXT_A), (OWNER, INSTANCE_A, str(uuid.uuid4())),
                                         (OWNER, INSTANCE_B, self.registry.instances[INSTANCE_B].snapshot['context'])]:
            with self.assertRaises((PermissionError, ValueError)):
                self.registry.command(owner, instance, context, 'callback', address())
        self.assertIsNone(self.registry.instances[INSTANCE_B].command)
        command = self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
        self.assertEqual(address(), command['callback'])
        self.assertEqual(command, self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address()))
        with self.assertRaises(ValueError):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address(code='different'))
        self.assertNotIn('synthetic-secret', repr(self.registry.instances[INSTANCE_A]))
        publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt()), ack=command['id'])
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    def test_expiry_offline_cancel_process_and_completion_invalidate_callbacks(self):
        for reason in ['expired', 'offline', 'cancel', 'process', 'complete']:
            self.setUp()
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
            if reason == 'expired':
                self.clock.advance(300)
                publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt()))
            elif reason == 'offline':
                self.clock.advance(46)
                self.registry.exchange(self.registry.instances[INSTANCE_A], {'runId': RUN_A})
            elif reason == 'cancel':
                publish(self.registry, value=snapshot('cancelled', context=str(uuid.uuid4())))
            elif reason == 'process':
                self.clock.advance(46)
                publish(self.registry, value=snapshot('signing_in', context=str(uuid.uuid4()), run=str(uuid.uuid4()), browserPrompt=prompt('T'*43)))
            else:
                publish(self.registry, value=snapshot('paired'))
            with self.subTest(reason=reason), self.assertRaises(ValueError):
                self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
            self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    def test_callback_is_transient_and_not_saved_or_restored(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            self.registry.state_path = path
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
            self.registry.remember_message(INSTANCE_A, 123, CONTEXT_A)
            stored = path.read_text()
            self.assertNotIn('synthetic-secret', stored)
            self.assertNotIn('authorizationUri', stored)
            restarted = Registry(config(), self.clock, self.clock, state_path=path)
            self.assertIsNone(restarted.instances[INSTANCE_A].command)
            with self.assertRaises(ValueError):
                restarted.callback_request(OWNER, INSTANCE_A, CONTEXT_A)
            publish(restarted, value=snapshot('signing_in', browserPrompt=prompt()))
            self.assertEqual(prompt(), restarted.callback_request(OWNER, INSTANCE_A, CONTEXT_A))

    def test_denied_consent_passes_to_instance_without_exposing_provider_message(self):
        callback = address().replace('code=synthetic-secret', 'error=access_denied&error_description=synthetic-secret')
        self.assertEqual(callback, validate_callback(prompt(), callback))


class BrowserDiscordTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.clock = Clock()
        self.registry = Registry(config(), self.clock, self.clock)
        publish(self.registry)
        self.delivery = FakeDiscord()
        self.notifications = Notifications(self.registry, self.delivery, self.clock)
        await self.notifications.reconcile()
        publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt()))
        self.bot = Bot(self.registry)

    async def asyncTearDown(self):
        await self.bot.close()

    def interaction(self, owner=OWNER, guild=None):
        return SimpleNamespace(user=SimpleNamespace(id=owner), guild=guild,
            response=SimpleNamespace(send_message=AsyncMock(), defer=AsyncMock(), send_modal=AsyncMock(), is_done=lambda:False),
            followup=SimpleNamespace(send=AsyncMock()))

    async def test_account_picker_copy_instructions_email_and_persistent_button(self):
        instance = self.registry.instances[INSTANCE_A]
        instance.login_email = 'alt@example.test'
        text, view = Delivery(self.bot).render(instance, True, prompt())
        self.assertIn('alt@example.test', text)
        self.assertIn('Choose Microsoft account', text)
        self.assertIn('localhost', text)
        self.assertIn('entire address', text)
        self.assertNotIn('synthetic-secret', text)
        button = next(b for b in view.children if b.label == 'Paste callback')
        self.assertFalse(button.disabled)
        self.assertTrue(view.is_persistent())
        self.assertLessEqual(len(button.custom_id), 100)
        interaction = self.interaction()
        await button.callback(interaction)
        self.assertIsInstance(interaction.response.send_modal.call_args.args[0], CallbackModal)
        interaction.response.defer.assert_not_awaited()
        self.assertIsNone(instance.command)

    async def test_nonowner_and_guild_cannot_open_or_submit_modal(self):
        for interaction in [self.interaction(OWNER+1), self.interaction(guild=object())]:
            await self.bot.open_callback(interaction, INSTANCE_A, CONTEXT_A)
            interaction.response.send_modal.assert_not_awaited()
            await self.bot.submit_callback(interaction, INSTANCE_A, CONTEXT_A, address())
            interaction.response.defer.assert_not_awaited()
            self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_modal_submission_defers_ephemerally_and_never_echoes_code(self):
        interaction = self.interaction()
        modal = CallbackModal(self.bot, INSTANCE_A, CONTEXT_A)
        modal.address._value = address()  # Synthetic SDK input; no Discord network.
        await modal.on_submit(interaction)
        interaction.response.defer.assert_awaited_once_with(thinking=True, ephemeral=True)
        self.assertEqual(address(), self.registry.instances[INSTANCE_A].command['callback'])
        self.assertNotIn('synthetic-secret', str(interaction.followup.send.call_args))
        self.assertTrue(interaction.followup.send.call_args.kwargs['ephemeral'])

    async def test_open_modal_rechecks_owner_context_and_expiry_at_submission(self):
        interaction = self.interaction()
        await self.bot.open_callback(interaction, INSTANCE_A, CONTEXT_A)
        self.clock.advance(300)
        publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt()))
        await self.bot.submit_callback(interaction, INSTANCE_A, CONTEXT_A, address())
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)
        self.assertIn('expired', interaction.followup.send.call_args.args[0])
        fresh = self.interaction()
        await self.bot.open_callback(fresh, INSTANCE_A, CONTEXT_A)
        fresh.response.send_modal.assert_not_awaited()

    async def test_offline_or_expired_prompts_removed_and_paste_disabled(self):
        await self.notifications.reconcile()
        self.assertEqual(prompt(), self.delivery.edits[-1][3])
        self.clock.advance(46)
        await self.notifications.reconcile()
        self.assertIsNone(self.delivery.edits[-1][3])
        publish(self.registry, value=snapshot('signing_in', browserPrompt=prompt(expires=46000)))
        await self.notifications.reconcile()
        self.assertIsNone(self.delivery.edits[-1][3])
        text, view = Delivery(self.bot).render(self.registry.instances[INSTANCE_A], True, None)
        self.assertNotIn('Choose Microsoft account', text)
        self.assertTrue(next(b for b in view.children if b.label == 'Paste callback').disabled)

    async def test_outage_removes_pending_callback_even_when_no_more_polls_arrive(self):
        self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
        self.delivery.blocked = True
        await self.notifications.reconcile()
        self.clock.advance(46)
        await self.notifications.reconcile()
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_cancel_supersedes_undelivered_callback(self):
        self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'callback', address())
        cancel = self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'cancel')
        self.assertEqual('cancel', cancel['action'])
        self.assertNotIn('callback', cancel)

    async def test_modal_errors_do_not_log_exception_payload(self):
        with self.assertLogs('sxp_remote', level='ERROR') as logs:
            await CallbackModal(self.bot, INSTANCE_A, CONTEXT_A).on_error(self.interaction(), Exception('synthetic-secret'))
        self.assertNotIn('synthetic-secret', str(logs.output))
