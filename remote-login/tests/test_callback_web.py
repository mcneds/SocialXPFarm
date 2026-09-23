import copy
import json
from pathlib import Path
from urllib.parse import urlencode, urlparse
import unittest

from aiohttp import ClientSession, DummyCookieJar
from aiohttp.test_utils import TestServer
from sxp_remote.app import Delivery, Bot
from sxp_remote.callback_web import CallbackService, cookie_name, make_callback_http
from sxp_remote.core import Registry, browser_parameters, validate_snapshot
from helpers import *

HTTPS_FIXTURE = json.loads((Path(__file__).parent / 'fixtures/https-snapshot.json').read_text())
SETTINGS = {'clientId':'00000000-0000-0000-0000-000000000099', 'redirectUri':'https://auth.example.test/oauth/callback', 'port':38472}


def https_config():
    return dict(config(), browserCallback=dict(SETTINGS))


def https_prompt(state='S' * 43):
    value = copy.deepcopy(HTTPS_FIXTURE['browserPrompt'])
    params = browser_parameters(value)
    params['state'] = state
    value['authorizationUri'] = value['authorizationUri'].split('?')[0] + '?' + urlencode(params)
    value['expiresAt'] = 300000
    return value


class CallbackStateTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.registry = Registry(https_config(), self.clock, self.clock)
        publish(self.registry, value=snapshot('signing_in', browserPrompt=https_prompt()))
        self.service = CallbackService(self.registry)

    def test_https_fixture_requires_explicit_matching_registration_and_form_post(self):
        self.assertEqual(HTTPS_FIXTURE, validate_snapshot(HTTPS_FIXTURE, RUN_A, SETTINGS))
        for settings in [None, dict(SETTINGS, clientId=INSTANCE_B), dict(SETTINGS, redirectUri='https://other.example.test/oauth/callback')]:
            with self.assertRaises(ValueError):
                validate_snapshot(HTTPS_FIXTURE, RUN_A, settings)
        wrong = copy.deepcopy(HTTPS_FIXTURE)
        wrong['browserPrompt']['authorizationUri'] = wrong['browserPrompt']['authorizationUri'].replace('response_mode=form_post', 'response_mode=query')
        with self.assertRaises(ValueError):
            validate_snapshot(wrong, RUN_A, SETTINGS)
        with self.assertRaises(ValueError):
            make_callback_http(Registry(config()))

    def test_unrelated_states_and_ambiguous_instances_are_not_delivered(self):
        for fields in [{}, {'state':'unknown','code':'synthetic-code'}, {'state':'S'*43,'code':'x','error':'denied'}]:
            with self.assertRaises(ValueError):
                self.service.accept(fields)
        publish(self.registry, INSTANCE_B, snapshot('signing_in', browserPrompt=https_prompt(), accountId=INSTANCE_B))
        with self.assertRaises(ValueError):
            self.service.accept({'state':'S'*43, 'code':'synthetic-code'})
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)
        self.assertIsNone(self.registry.instances[INSTANCE_B].command)
        self.assertEqual({}, self.service.receipts)

    def test_callback_is_one_time_even_after_command_acknowledgement(self):
        key, secret = self.service.accept({'state':'S'*43,'code':'synthetic-code'})
        command = self.registry.instances[INSTANCE_A].command
        self.assertEqual('callback', command['action'])
        self.assertEqual('checking', self.service.status(key, secret))
        publish(self.registry, value=snapshot('signing_in', browserPrompt=https_prompt()), ack=command['id'])
        with self.assertRaises(ValueError):
            self.service.accept({'state':'S'*43,'code':'synthetic-code'})
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)
        self.assertNotIn('synthetic-code', repr(self.service.receipts))
        publish(self.registry, value=snapshot('paired'))
        self.assertEqual('complete', self.service.status(key, secret))
        self.assertEqual('expired', self.service.status(key, 'wrong-cookie'))

    def test_concurrent_alts_are_isolated_by_state_and_receipt(self):
        publish(self.registry, INSTANCE_B, snapshot('signing_in', browserPrompt=https_prompt('T'*43), accountId=INSTANCE_B))
        a, sa = self.service.accept({'state':'S'*43,'code':'synthetic-a'})
        b, sb = self.service.accept({'state':'T'*43,'code':'synthetic-b'})
        self.assertNotEqual(a, b)
        self.assertNotEqual(sa, sb)
        publish(self.registry, value=snapshot('signed_in'))
        self.assertEqual('complete', self.service.status(a, sa))
        self.assertEqual('checking', self.service.status(b, sb))
        self.assertEqual('expired', self.service.status(a, sb))
        self.assertNotIn('synthetic-a', self.registry.instances[INSTANCE_B].command['callback'])

    def test_closed_attempt_account_changes_offline_and_restart_never_claim_success(self):
        for reason in ['cancelled', 'failed', 'needs_login', 'disabled', 'context', 'account', 'process', 'offline', 'restart']:
            self.setUp()
            key, secret = self.service.accept({'state':'S'*43,'code':'synthetic-code'})
            if reason == 'offline':
                self.clock.advance(46)
            elif reason == 'restart':
                self.service = CallbackService(self.registry)
            elif reason == 'context':
                publish(self.registry, value=snapshot('paired', context=str(uuid.uuid4())))
            elif reason == 'account':
                publish(self.registry, value=snapshot('paired', accountId=INSTANCE_B))
            elif reason == 'process':
                self.clock.advance(46)
                publish(self.registry, value=snapshot('paired', run=str(uuid.uuid4())))
            else:
                publish(self.registry, value=snapshot(reason))
            with self.subTest(reason=reason):
                self.assertIn(self.service.status(key, secret), {'ended','offline','expired'})

    def test_expiry_removes_receipt_and_pending_request_cannot_be_replayed(self):
        key, secret = self.service.accept({'state':'S'*43,'code':'synthetic-code'})
        self.clock.advance(300)
        publish(self.registry, value=snapshot('signing_in', browserPrompt=https_prompt()))
        self.assertEqual('expired', self.service.status(key, secret))
        self.assertEqual({}, self.service.receipts)
        self.assertEqual({}, self.service.used)
        with self.assertRaises(ValueError):
            self.service.accept({'state':'S'*43,'code':'synthetic-code'})


class CallbackHttpTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.clock = Clock()
        self.registry = Registry(https_config(), self.clock, self.clock)
        publish(self.registry, value=snapshot('signing_in', browserPrompt=https_prompt()))
        self.server = TestServer(make_callback_http(self.registry), host='127.0.0.1')
        await self.server.start_server()
        self.http = ClientSession(cookie_jar=DummyCookieJar())
        self.headers = {'Host':'auth.example.test'}

    async def asyncTearDown(self):
        await self.http.close()
        await self.server.close()

    async def post(self, fields=None):
        response = await self.http.post(self.server.make_url('/oauth/callback'),
            data=fields or {'state':'S'*43,'code':'synthetic-code'}, headers=self.headers, allow_redirects=False)
        return response

    async def test_form_post_clean_redirect_cookie_and_verified_confirmation(self):
        async with await self.post() as response:
            self.assertEqual(303, response.status)
            path = response.headers['Location']
            key = path.rsplit('/',1)[1]
            cookie = response.cookies[cookie_name(key)]
            self.assertTrue(cookie['secure'])
            self.assertTrue(cookie['httponly'])
            self.assertEqual('Lax', cookie['samesite'])
            self.assertEqual(path, cookie['path'])
            self.assertEqual('300', cookie['max-age'])
            self.assertNotIn('synthetic-code', str(response.headers))
            self.assertEqual('no-store', response.headers['Cache-Control'])
        headers = dict(self.headers, Cookie=cookie_name(key)+'='+cookie.value)
        async with self.http.get(self.server.make_url(path), headers=headers) as page:
            body = await page.text()
            self.assertIn('Checking your Minecraft account', body)
            self.assertNotIn('synthetic-code', body)
            self.assertNotIn(cookie.value, body)
            self.assertIn("frame-ancestors 'none'", page.headers['Content-Security-Policy'])
            self.assertEqual('no-referrer', page.headers['Referrer-Policy'])
        async with self.http.get(self.server.make_url(path+'/status'), headers=headers) as status:
            self.assertEqual('checking', (await status.json())['state'])
        publish(self.registry, value=snapshot('paired'))
        async with self.http.get(self.server.make_url(path+'/status'), headers=headers) as status:
            self.assertEqual('complete', (await status.json())['state'])
        async with self.http.get(self.server.make_url(path+'/status'), headers=self.headers) as status:
            self.assertEqual('expired', (await status.json())['state'])

    async def test_legacy_paste_control_removed_only_for_hosted_requests(self):
        bot = Bot(self.registry)
        try:
            instance = self.registry.instances[INSTANCE_A]
            text, view = Delivery(bot).render(instance, True, https_prompt())
            self.assertNotIn('localhost', text)
            self.assertNotIn('entire address', text)
            self.assertIn('updates automatically', text)
            self.assertFalse(any(b.label=='Paste callback' for b in view.children))
        finally:
            await bot.close()

    async def test_wrong_host_methods_routes_and_content_type_cannot_control_instances(self):
        for method, path, headers, body in [
            ('post','/oauth/callback',{'Host':'evil.test'},'state=x&code=synthetic-secret'),
            ('get','/oauth/callback?state=x&code=synthetic-secret',self.headers,None),
            ('post','/v1/instances/'+INSTANCE_A+'/exchange',self.headers,'{}'),
            ('post','/login',self.headers,'{}'),
            ('post','/oauth/callback',self.headers,'{"code":"synthetic-secret"}')]:
            async with self.http.request(method, self.server.make_url(path), headers=headers, data=body) as response:
                self.assertIn(response.status, {400,403,404,405})
                self.assertNotIn('synthetic-secret', await response.text())
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_malformed_duplicates_oversized_and_invalid_utf8_bodies_fail_safely(self):
        for data in ['state='+('S'*43)+'&state=duplicate&code=x', 'state=%ZZ&code=x',
                     'state='+('S'*43)+'&code=x&error=denied', 'state='+('S'*43)+'&code=',
                     'state='+('S'*43)+'&code='+('x'*5000), 'x'*9000, b'\xff']:
            async with self.http.post(self.server.make_url('/oauth/callback'), data=data,
                    headers=dict(self.headers, **{'Content-Type':'application/x-www-form-urlencoded'})) as response:
                self.assertIn(response.status, {400,413})
        self.assertIsNone(self.registry.instances[INSTANCE_A].command)

    async def test_denied_consent_drops_provider_details_and_replay_returns_no_new_cookie(self):
        async with await self.post({'state':'S'*43, 'error':'access_denied', 'error_description':'synthetic-secret'}) as response:
            self.assertEqual(303,response.status)
        self.assertNotIn('synthetic-secret', self.registry.instances[INSTANCE_A].command['callback'])
        async with await self.post() as response:
            self.assertEqual(400,response.status)
            self.assertNotIn('Set-Cookie', response.headers)

    async def test_rate_limits_bound_invalid_callback_traffic_and_recover(self):
        for _ in range(20):
            async with await self.post({'state':'unknown','code':'synthetic'}) as response:
                self.assertEqual(400,response.status)
        async with await self.post() as response:
            self.assertEqual(429,response.status)
        self.clock.advance(1)
        async with await self.post() as response:
            self.assertEqual(303,response.status)
