import tempfile
import unittest
from pathlib import Path
import uuid
from sxp_remote.core import Registry
from sxp_remote.notifications import Notifications
from helpers import *


class NotificationTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.clock = Clock()
        self.registry = Registry(config(), self.clock, lambda: self.clock())
        self.discord = FakeDiscord()
        self.notifier = Notifications(self.registry, self.discord, self.clock)

    async def test_alert_once_then_code_and_success_edit_same_message(self):
        publish(self.registry)
        for _ in range(10):
            await self.notifier.reconcile()
        self.assertEqual(1, len(self.discord.sent))
        context = str(uuid.uuid4())
        prompt = {'userCode': 'ABCD-EFGH', 'verificationUri': 'https://microsoft.com/devicelogin', 'expiresAt': 900000}
        publish(self.registry, value=snapshot('signing_in', context=context, prompt=prompt))
        await self.notifier.reconcile()
        self.assertEqual(prompt, self.discord.edits[-1][3])
        for phase in ['signed_in', 'restored']:
            publish(self.registry, value=snapshot(phase, context=context))
            await self.notifier.reconcile()
        self.assertEqual(1, len(self.discord.sent))
        self.assertIsNone(self.discord.edits[-1][3])

    async def test_normal_silent_refresh_and_disabled_instances_do_not_alert(self):
        for phase in ['idle', 'renewing', 'signed_in', 'restored', 'disabled']:
            publish(self.registry, value=snapshot(phase))
            await self.notifier.reconcile()
        self.assertEqual([], self.discord.sent)

    async def test_two_alts_alert_independently(self):
        publish(self.registry)
        publish(self.registry, INSTANCE_B, snapshot(context=str(uuid.uuid4()), username='AltB', accountId=INSTANCE_B))
        await self.notifier.reconcile()
        self.assertEqual({INSTANCE_A, INSTANCE_B}, {x[0] for x in self.discord.sent})

    async def test_blocked_dms_wait_then_deliver_without_losing_alert(self):
        self.discord.blocked = True
        publish(self.registry)
        await self.notifier.reconcile()
        self.assertEqual(1, self.discord.failures)
        self.clock.advance(30)
        publish(self.registry)
        await self.notifier.reconcile()
        self.assertEqual(1, self.discord.failures)
        self.discord.blocked = False
        self.clock.advance(30)
        publish(self.registry)
        await self.notifier.reconcile()
        self.assertEqual(1, len(self.discord.sent))

    async def test_offline_or_expired_prompt_is_removed_from_message(self):
        publish(self.registry)
        await self.notifier.reconcile()
        prompt = {'userCode': 'ABCD-EFGH', 'verificationUri': 'https://microsoft.com/devicelogin', 'expiresAt': 10000}
        publish(self.registry, value=snapshot('signing_in', prompt=prompt))
        await self.notifier.reconcile()
        self.clock.advance(11)
        await self.notifier.reconcile()
        self.assertIsNone(self.discord.edits[-1][3])
        self.clock.advance(35)
        await self.notifier.reconcile()
        self.assertFalse(self.discord.edits[-1][2])

    async def test_restart_reuses_notification_identifier_without_second_alert(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            self.registry.state_path = path
            publish(self.registry)
            await self.notifier.reconcile()
            fresh = Registry(config(), self.clock, state_path=path)
            publish(fresh)
            await Notifications(fresh, self.discord, self.clock).reconcile()
            self.assertEqual(1, len(self.discord.sent))
            self.assertEqual(100 + 1, self.discord.edits[-1][0])

    async def test_new_episode_retires_old_button_and_sends_new_alert(self):
        publish(self.registry)
        await self.notifier.reconcile()
        publish(self.registry, value=snapshot(context=str(uuid.uuid4())))
        await self.notifier.reconcile()
        self.assertEqual(2, len(self.discord.sent))
        self.assertEqual([101], self.discord.retired)

    async def test_deleted_message_can_be_recreated(self):
        publish(self.registry)
        await self.notifier.reconcile()
        self.discord.missing = True
        publish(self.registry, value=snapshot('cancelled'))
        await self.notifier.reconcile()
        self.assertEqual(2, len(self.discord.sent))
