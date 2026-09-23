"""Isolated HTTPS callback origin. Never exposes the private instance-control API."""
import asyncio
import contextlib
from dataclasses import dataclass, field
import hmac
import logging
import secrets
from urllib.parse import urlencode, urlparse
from aiohttp import web

from .core import browser_parameters, unique_query

NONCE = web.RequestKey('nonce', str)
LOG = logging.getLogger('sxp_remote')


@dataclass(repr=False)
class Receipt:
    instance: str
    run: str
    context: str
    account: str
    state: str = field(repr=False)
    secret: str = field(repr=False)
    deadline: float
    result: str | None = None


class CallbackService:
    def __init__(self, registry):
        if registry.browser_callback is None:
            raise ValueError('Browser callback is not configured')
        self.registry = registry
        self.settings = registry.browser_callback
        self.receipts = {}
        self.used = {}
        self.budgets = {}

    def prune(self):
        now = self.registry.clock()
        for key, receipt in list(self.receipts.items()):
            if receipt.deadline <= now:
                del self.receipts[key]
                self.used.pop(receipt.state, None)

    def allow(self, kind):
        # Bounded global budgets also protect against spoofed client-IP headers.
        capacity, rate = (20, 1) if kind == 'callback' else (120, 10)
        now = self.registry.clock()
        amount, last = self.budgets.get(kind, (capacity, now))
        amount = min(capacity, amount + max(0, now - last) * rate)
        self.budgets[kind] = (max(0, amount - 1), now)
        return amount >= 1

    def accept(self, fields):
        self.prune()
        state = fields.get('state', '')
        if state in self.used:
            raise ValueError('Callback already received. Return to Discord for the result.')
        matches = []
        for instance in self.registry.instances.values():
            snapshot = instance.snapshot or {}
            prompt = snapshot.get('browserPrompt')
            if not self.registry.online(instance) or snapshot.get('state') != 'signing_in' or not prompt:
                continue
            if prompt['expiresAt'] <= self.registry.wall() * 1000:
                continue
            params = browser_parameters(prompt)
            if (params['redirect_uri'] == self.settings['redirectUri']
                    and params['client_id'] == self.settings['clientId']
                    and hmac.compare_digest(params['state'], state)):
                matches.append(instance)
        if len(matches) != 1 or len(self.receipts) >= 128:
            raise ValueError('This sign-in is unavailable or expired. Return to Discord and start again.')
        if ('code' in fields) == ('error' in fields) or not fields.get('code', fields.get('error', '')).strip():
            raise ValueError('Invalid sign-in response. Return to Discord and start again.')
        instance = matches[0]
        snapshot = instance.snapshot
        # Drop provider descriptions and any unrelated fields. Never fetch the submitted URL.
        selected = {'state': state, 'code': fields['code']} if 'code' in fields else {'state': state, 'error': 'access_denied'}
        address = self.settings['redirectUri'] + '?' + urlencode(selected)
        self.registry.command(self.registry.owner, instance.id, snapshot['context'], 'callback', address)
        key, secret = secrets.token_hex(16), secrets.token_urlsafe(32)
        self.receipts[key] = Receipt(instance.id, snapshot['runId'], snapshot['context'], snapshot['accountId'],
                                     state, secret, self.registry.clock() + 300)
        self.used[state] = key
        return key, secret

    def status(self, key, secret):
        self.prune()
        receipt = self.receipts.get(key)
        if receipt is None or not isinstance(secret, str) or not hmac.compare_digest(receipt.secret, secret):
            return 'expired'
        if receipt.result:
            return receipt.result
        instance = self.registry.instances.get(receipt.instance)
        if instance is None or not self.registry.online(instance):
            return 'offline'
        snapshot = instance.snapshot
        if (snapshot['runId'], snapshot['context'], snapshot['accountId']) != (receipt.run, receipt.context, receipt.account):
            receipt.result = 'ended'
        elif snapshot['state'] in {'paired', 'signed_in', 'restored'}:
            receipt.result = 'complete'
        elif snapshot['state'] in {'needs_login', 'failed', 'cancelled', 'disabled', 'idle'}:
            receipt.result = 'ended'
        return receipt.result or 'checking'


MESSAGES = {
    'checking': 'Checking your Minecraft account…',
    'complete': 'Sign-in complete. You can return to Discord.',
    'ended': 'Sign-in did not complete. Return to Discord to check the instance and try again.',
    'expired': 'This confirmation page expired. Check Discord for the result.',
    'offline': 'The instance is currently offline. Check Discord before starting another sign-in.',
}


def cookie_name(key):
    return 'sxp_receipt_' + key


def result_page(nonce):
    # All text is fixed. No account, code, OAuth state or provider text is inserted into HTML.
    return '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SocialXPFarm sign-in</title><style nonce="NONCE">body{font:18px system-ui;background:#111827;color:#f9fafb;margin:0;padding:3rem 1.5rem}main{max-width:32rem;margin:3rem auto}p{line-height:1.6;color:#d1d5db}</style>
<main><h1>SocialXPFarm sign-in</h1><p>NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.</p><p id="status">Checking your Minecraft account…</p><noscript>Return to Discord to check the result.</noscript></main>
<script nonce="NONCE">
const line = document.getElementById('status');
const deadline = Date.now() + 300000;
async function check() {
  try {
    const response = await fetch(location.pathname + '/status', {cache:'no-store', credentials:'same-origin'});
    if (!response.ok) throw new Error();
    const result = await response.json();
    line.textContent = result.message;
    if (['complete','ended','expired'].includes(result.state)) return;
  } catch (_) { line.textContent = 'Connection interrupted. You can check the result in Discord.'; }
  if (Date.now() < deadline) setTimeout(check, 2000);
  else line.textContent = 'This confirmation page expired. Check Discord for the result.';
}
check();
</script></html>'''.replace('NONCE', nonce)


def make_callback_http(registry):
    service = CallbackService(registry)
    host = urlparse(service.settings['redirectUri']).netloc

    @web.middleware
    async def protect(request, handler):
        nonce = secrets.token_urlsafe(24)
        request[NONCE] = nonce
        try:
            if request.host != host:
                response = web.Response(status=403, text='Forbidden')
            elif not service.allow('callback' if request.path == '/oauth/callback' else 'status'):
                response = web.Response(status=429, text='Please wait and try again.', headers={'Retry-After': '5'})
            else:
                response = await handler(request)
        except web.HTTPException as error:
            response = web.Response(status=error.status, text='Request unavailable. Return to Discord.')
        except Exception:
            # Neither aiohttp nor a traceback may log a callback body or receipt cookie.
            LOG.error('Browser callback handler failed; sensitive details omitted.')
            response = web.Response(status=500, text='Sign-in could not be checked. Return to Discord.')
        response.headers.update({
            'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
            'Content-Security-Policy': f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'nonce-{nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            'Strict-Transport-Security': 'max-age=31536000',
        })
        return response

    app = web.Application(client_max_size=8192, middlewares=[protect])

    async def receipt_lifetime(app):
        async def expire():
            while True:
                service.prune()
                await asyncio.sleep(15)
        task = asyncio.create_task(expire())
        try:
            yield
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            service.receipts.clear()
            service.used.clear()

    app.cleanup_ctx.append(receipt_lifetime)

    async def callback(request):
        if request.query_string or request.content_type != 'application/x-www-form-urlencoded':
            return web.Response(status=400, text='Invalid sign-in response. Return to Discord.')
        try:
            fields = unique_query(await request.text())
            key, secret = service.accept(fields)
        except (ValueError, TypeError, UnicodeError):
            return web.Response(status=400, text='This response is invalid, expired or already received. Check Discord for the result or start a new sign-in.')
        path = '/oauth/result/' + key
        response = web.Response(status=303, headers={'Location': path})
        response.set_cookie(cookie_name(key), secret, max_age=300, path=path, secure=True, httponly=True, samesite='Lax')
        return response

    async def result(request):
        return web.Response(text=result_page(request[NONCE]), content_type='text/html')

    async def status(request):
        key = request.match_info['receipt']
        state = service.status(key, request.cookies.get(cookie_name(key)))
        return web.json_response({'state': state, 'message': MESSAGES[state]})

    app.router.add_post('/oauth/callback', callback)
    app.router.add_get('/oauth/result/{receipt:[0-9a-f]{32}}', result)
    app.router.add_get('/oauth/result/{receipt:[0-9a-f]{32}}/status', status)
    return app
