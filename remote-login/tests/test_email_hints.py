import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

from sxp_remote.app import Bot, Delivery
from sxp_remote.config import load, login_email, private_write
from sxp_remote.core import Registry
from sxp_remote.notifications import Notifications
from helpers import config, snapshot, publish, Clock, FakeDiscord, INSTANCE_A, INSTANCE_B, OWNER


class EmailHintTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'private/config.json'
        private_write(self.path, config())
        self.registry = Registry(load(self.path), config_path=self.path)

    def test_missing_hint_is_backward_compatible_and_email_validation_is_safe(self):
        self.assertEqual('', self.registry.instances[INSTANCE_A].login_email)
        self.assertEqual('alt+one@example.com', login_email(' alt+one@example.com '))
        for value in ['not an email', 'a@b\nnew=value', '<@123>', '`name`@example.com', 'a'*255+'@example.com', None]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                login_email(value)

    def test_update_is_per_instance_persistent_and_keeps_other_configuration(self):
        expected = config()
        expected['instances'][0]['loginEmail'] = 'alt.one@example.com'
        self.registry.set_login_email(OWNER, INSTANCE_A, 'alt.one@example.com')
        self.assertEqual(expected, load(self.path))
        restarted = Registry(load(self.path))
        self.assertEqual('alt.one@example.com', restarted.instances[INSTANCE_A].login_email)
        self.assertEqual('', restarted.instances[INSTANCE_B].login_email)
        self.assertNotIn('alt.one@example.com', repr(restarted.instances[INSTANCE_A]))
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        self.registry.set_login_email(OWNER, INSTANCE_A, '')
        self.assertEqual(config(), load(self.path))
        self.assertEqual('', self.registry.instances[INSTANCE_A].login_email)

    def test_unauthorized_or_failed_write_does_not_change_memory_or_disk(self):
        with self.assertRaises(PermissionError):
            self.registry.set_login_email(OWNER+1, INSTANCE_A, 'other@example.com')
        with patch('sxp_remote.core.private_write', side_effect=OSError), self.assertRaises(OSError):
            self.registry.set_login_email(OWNER, INSTANCE_A, 'other@example.com')
        self.assertEqual(config(), load(self.path))
        self.assertEqual('', self.registry.instances[INSTANCE_A].login_email)

    def test_owner_change_requires_restart_before_old_owner_can_write(self):
        changed = config()
        changed['ownerId'] = str(OWNER+1)
        private_write(self.path, changed)
        with self.assertRaisesRegex(ValueError, 'owner changed'):
            self.registry.set_login_email(OWNER, INSTANCE_A, 'other@example.com')
        self.assertEqual(changed, load(self.path))


class EmailDiscordTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'private/config.json'
        private_write(self.path, config())
        self.registry = Registry(load(self.path), config_path=self.path)
        self.bot = Bot(self.registry)

    async def asyncTearDown(self):
        await self.bot.close()
        self.temp.cleanup()

    def interaction(self, owner=OWNER, guild=None):
        return SimpleNamespace(user=SimpleNamespace(id=owner), guild=guild,
            response=SimpleNamespace(send_message=AsyncMock(), defer=AsyncMock()),
            followup=SimpleNamespace(send=AsyncMock()))

    async def test_owner_can_set_read_and_clear_email_by_label_even_when_instance_offline(self):
        self.assertIsNotNone(self.bot.tree.get_command('sxp').get_command('email'))
        interaction = self.interaction()
        await self.bot.email_action(interaction, 'alt a', 'alt.one@example.com')
        self.assertEqual('alt.one@example.com', load(self.path)['instances'][0]['loginEmail'])
        self.assertIn('alt.one@example.com', interaction.followup.send.call_args.args[0])
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)
        await self.bot.email_action(self.interaction(), INSTANCE_A)
        await self.bot.email_action(self.interaction(), INSTANCE_A, 'clear')
        self.assertNotIn('loginEmail', load(self.path)['instances'][0])

    async def test_nonowner_or_guild_cannot_read_or_write_hints(self):
        for interaction in [self.interaction(OWNER+1), self.interaction(guild=object())]:
            await self.bot.email_action(interaction, INSTANCE_A, 'not-owner@example.com')
            interaction.response.defer.assert_not_awaited()
            self.assertIn('configured owner', interaction.response.send_message.call_args.args[0])
        self.assertEqual(config(), load(self.path))

    async def test_message_identifies_email_and_explains_private_browser_without_modifying_microsoft_link(self):
        self.registry.set_login_email(OWNER, INSTANCE_A, 'alt.one@example.com')
        publish(self.registry, value=snapshot('signing_in'))
        prompt = {'userCode': 'TEST-CODE', 'verificationUri': 'https://microsoft.com/devicelogin', 'expiresAt': 2000000000000}
        text, _ = Delivery(self.bot).render(self.registry.instances[INSTANCE_A], True, prompt)
        self.assertIn('Microsoft email to use (configured): alt.one@example.com', text)
        self.assertIn('Private/Incognito', text)
        self.assertIn('Close previous private tabs', text)
        self.assertIn('Use another account', text)
        self.assertIn(prompt['verificationUri'], text)
        self.assertNotIn('login_hint=', text)
        self.assertNotIn('prompt=', text)
        self.assertLess(len(text), 2000)

    async def test_missing_hint_gives_setup_instruction_without_guessing_email(self):
        publish(self.registry)
        text, _ = Delivery(self.bot).render(self.registry.instances[INSTANCE_A], True, None)
        self.assertIn('email hint not set', text)
        self.assertIn('/sxp email', text)
        self.assertNotIn('@', text)

    async def test_hint_change_updates_existing_request_without_a_second_notification(self):
        clock, delivery = Clock(), FakeDiscord()
        notifier = Notifications(self.registry, delivery, clock)
        publish(self.registry)
        await notifier.reconcile()
        self.registry.set_login_email(OWNER, INSTANCE_A, 'alt.one@example.com')
        await notifier.reconcile()
        self.assertEqual(1, len(delivery.sent))
        self.assertEqual(1, len(delivery.edits))
