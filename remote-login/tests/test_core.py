import copy
import tempfile
import unittest
from pathlib import Path
import uuid

from sxp_remote.core import Registry, validate_snapshot
from helpers import *


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.registry = Registry(config(), self.clock, lambda: 1000 + self.clock())
        self.instance = self.registry.instances[INSTANCE_A]
        publish(self.registry)

    def test_separate_instance_credentials_and_unknown_instance_rejected(self):
        self.assertIs(self.instance, self.registry.authenticate(INSTANCE_A, 'Bearer ' + SECRET_A))
        for key, secret in [(INSTANCE_A, SECRET_B), (INSTANCE_B, SECRET_A), ('unknown', SECRET_A), (INSTANCE_A, '')]:
            with self.subTest(key=key), self.assertRaises(PermissionError):
                self.registry.authenticate(key, 'Bearer ' + secret)
        self.assertNotIn(SECRET_A, repr(self.instance))

    def test_owner_is_checked_even_with_a_valid_context(self):
        with self.assertRaises(PermissionError):
            self.registry.command(OWNER + 1, INSTANCE_A, CONTEXT_A, 'login')
        self.assertIsNone(self.instance.command)

    def test_duplicate_clicks_deliver_one_idempotent_command_until_ack(self):
        first = self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        self.assertEqual(first, self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login'))
        result = publish(self.registry)
        self.assertEqual(first, result['command'])
        self.assertIsNone(publish(self.registry, ack=first['id'])['command'])

    def test_other_alt_cannot_receive_command(self):
        publish(self.registry, INSTANCE_B, snapshot(run=str(uuid.uuid4()), context=str(uuid.uuid4()), accountId=INSTANCE_B))
        self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        other = self.registry.instances[INSTANCE_B]
        result = self.registry.exchange(other, {'runId': other.snapshot['runId'], 'ack': ''})
        self.assertIsNone(result['command'])

    def test_stale_context_unknown_action_and_unavailable_states_rejected(self):
        for phase in ['idle', 'disabled', 'renewing', 'restored', 'failed', 'signed_in', 'signing_in']:
            publish(self.registry, value=snapshot(phase, canLogin=True))
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        publish(self.registry)
        for context, action in [(str(uuid.uuid4()), 'login'), (CONTEXT_A, 'enable'), (CONTEXT_A, 'shell')]:
            with self.subTest(action=action), self.assertRaises(ValueError):
                self.registry.command(OWNER, INSTANCE_A, context, action)

    def test_idle_without_context_reports_no_pending_recovery_but_rejects_old_buttons_as_stale(self):
        publish(self.registry, value=snapshot('idle', context='', canLogin=False))
        for action in ('login', 'cancel'):
            with self.subTest(action=action), self.assertRaisesRegex(ValueError, 'No authentication recovery is pending'):
                self.registry.command(OWNER, INSTANCE_A, '', action)
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        self.assertIsNone(self.instance.command)

    def test_cancel_is_only_for_phone_login(self):
        with self.assertRaises(ValueError):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'cancel')
        publish(self.registry, value=snapshot('signing_in'))
        self.assertEqual('cancel', self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'cancel')['action'])

    def test_remote_test_requires_capability_and_is_idempotent_and_instance_scoped(self):
        publish(self.registry, value=snapshot('idle', canTest=True))
        first = self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'test')
        self.assertEqual('test', first['action'])
        self.assertEqual(first, self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'test'))
        self.assertIsNone(self.registry.instances[INSTANCE_B].command)
        with self.assertRaises(PermissionError):
            self.registry.command(OWNER+1, INSTANCE_A, CONTEXT_A, 'test')
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.registry.command(OWNER, INSTANCE_A, str(uuid.uuid4()), 'test')
        publish(self.registry, value=snapshot('needs_login', context=str(uuid.uuid4())))
        self.assertIsNone(self.instance.command)

    def test_remote_test_rejects_old_mods_offline_and_active_or_disabled_states(self):
        for changes in ({}, {'canTest': False}, {'canTest': True, 'state': 'signing_in'}, {'canTest': True, 'state': 'disabled'}):
            value = snapshot('idle')
            value.update(changes)
            publish(self.registry, value=value)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'test')
        publish(self.registry, value=snapshot('idle', canTest=True))
        self.clock.advance(46)
        with self.assertRaisesRegex(ValueError, 'offline'):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'test')

    def test_completed_pairing_can_be_tested_again_but_cannot_start_normal_login(self):
        publish(self.registry, value=snapshot('paired', canTest=True))
        with self.assertRaises(ValueError):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        self.assertEqual('test', self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'test')['action'])

    def test_test_capability_must_be_boolean(self):
        with self.assertRaises(ValueError):
            validate_snapshot(snapshot(canTest='true'), RUN_A)

    def test_new_context_account_or_process_invalidates_queued_work(self):
        for change in ['context', 'accountId', 'runId']:
            with self.subTest(change=change):
                publish(self.registry)
                self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
                if change == 'runId':
                    self.clock.advance(46)
                publish(self.registry, value=snapshot(**{change if change != 'context' else 'context': str(uuid.uuid4())}))
                self.assertIsNone(self.instance.command)
                self.clock.advance(46)

    def test_active_duplicate_process_is_rejected_then_restart_can_register(self):
        newer = snapshot(run=str(uuid.uuid4()))
        with self.assertRaises(RuntimeError):
            publish(self.registry, value=newer)
        self.clock.advance(45)
        publish(self.registry, value=newer)
        self.assertEqual(newer['runId'], self.instance.snapshot['runId'])

    def test_heartbeat_expiry_prevents_stale_ui_work_even_when_transport_polls(self):
        self.clock.advance(44)
        self.registry.exchange(self.instance, {'runId': RUN_A, 'ack': ''})
        self.clock.advance(1)
        self.assertFalse(self.registry.online(self.instance))
        with self.assertRaises(ValueError):
            self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')

    def test_commands_expire_after_sixty_seconds_even_if_instance_is_online(self):
        self.registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        for _ in range(4):
            self.clock.advance(15)
            result = publish(self.registry)
        self.assertIsNone(result['command'])

    def test_missing_snapshot_requests_resync_after_service_restart(self):
        fresh = Registry(config())
        self.assertEqual({'resync': True}, fresh.exchange(fresh.instances[INSTANCE_A], {'runId': RUN_A}))

    def test_private_oauth_fields_and_malformed_snapshots_rejected(self):
        for field in ['device_code', 'access_token', 'refresh_token', 'secret', 'botToken']:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_snapshot(snapshot(**{field: 'synthetic-secret'}), RUN_A)
        for change in [{'username': '@everyone'}, {'canLogin': 1}, {'context': None}, {'message': 'x'*513}, {'accountId': 'bad'}]:
            with self.subTest(change=change), self.assertRaises((ValueError, TypeError)):
                validate_snapshot(snapshot(**change), RUN_A)

    def test_only_public_prompt_fields_and_exact_microsoft_hosts_allowed(self):
        good = {'userCode': 'ABCD-EFGH', 'verificationUri': 'https://microsoft.com/devicelogin', 'expiresAt': 12345}
        self.assertEqual(good, validate_snapshot(snapshot('signing_in', prompt=good), RUN_A)['prompt'])
        for bad in [dict(good, device_code='private'), dict(good, verificationUri='https://microsoft.com.evil.test/'),
                    dict(good, verificationUri='http://microsoft.com/'), dict(good, userCode='<script>')]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_snapshot(snapshot('signing_in', prompt=bad), RUN_A)

    def test_state_persistence_contains_no_codes_or_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'state.json'
            registry = Registry(config(), self.clock, state_path=path)
            publish(registry, value=snapshot('signing_in', prompt={'userCode':'ABCD-EFGH', 'verificationUri':'https://microsoft.com/devicelogin', 'expiresAt':12345}))
            registry.remember_message(INSTANCE_A, 100, CONTEXT_A)
            text = path.read_text()
            for secret in [SECRET_A, 'ABCD-EFGH', 'botToken', 'refresh_token', 'device_code']:
                self.assertNotIn(secret, text)
            restarted = Registry(config(), self.clock, state_path=path)
            self.assertEqual(registry.messages, restarted.messages)
            self.assertFalse(restarted.online(restarted.instances[INSTANCE_A]))

    def test_shared_java_snapshot_fixture_is_accepted_without_private_fields(self):
        import json
        value = json.loads((Path(__file__).parent/'fixtures/snapshot.json').read_text())
        self.assertEqual(value, validate_snapshot(value, value['runId']))
