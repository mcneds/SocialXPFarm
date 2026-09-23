"""Pure protocol/state machine; no Discord SDK and no reusable Microsoft tokens. Callback codes are transient and never persisted."""
from dataclasses import dataclass, field
import hmac
import json
from pathlib import Path
import re
import time
from urllib.parse import urlparse, parse_qsl
import uuid

from .config import private_write, load, login_email

STATES = {'idle', 'disabled', 'renewing', 'needs_login', 'signing_in', 'signed_in', 'restored', 'cancelled', 'failed', 'paired'}
SNAPSHOT_KEYS = {'runId', 'context', 'username', 'accountId', 'state', 'message', 'canLogin', 'prompt', 'canTest', 'browserPrompt'}


def identifier(value):
    if not isinstance(value, str):
        raise ValueError('Invalid identifier')
    return str(uuid.UUID(value))


def unique_query(raw):
    if re.search(r'%(?![0-9a-fA-F]{2})', raw):
        raise ValueError('Invalid query encoding')
    pairs = parse_qsl(raw, keep_blank_values=True, strict_parsing=True, errors='strict', max_num_fields=20)
    values = dict(pairs)
    if len(values) != len(pairs):
        raise ValueError('Duplicate query parameter')
    return values


def browser_parameters(prompt):
    try:
        if not isinstance(prompt, dict) or set(prompt) != {'authorizationUri', 'expiresAt'} or type(prompt['expiresAt']) is not int:
            raise ValueError()
        address = prompt['authorizationUri']
        if not isinstance(address, str) or len(address) > 2000:
            raise ValueError()
        uri = urlparse(address)
        if (address.split('?')[0] != 'https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize'
                or uri.fragment):
            raise ValueError()
        query = unique_query(uri.query)
        if (set(query) != {'client_id', 'response_type', 'redirect_uri', 'scope', 'state', 'prompt', 'code_challenge', 'code_challenge_method'}
                or query['response_type'] != 'code' or query['prompt'] != 'select_account'
                or query['scope'] != 'XboxLive.signin offline_access' or query['code_challenge_method'] != 'S256'
                or not re.fullmatch(r'[A-Za-z0-9_-]{43}', query['state'])
                or not re.fullmatch(r'[A-Za-z0-9_-]{43}', query['code_challenge'])
                or not re.fullmatch(r'http://localhost:[0-9]{1,5}/callback', query['redirect_uri'])
                or not 1 <= urlparse(query['redirect_uri']).port <= 65535):
            raise ValueError()
        identifier(query['client_id'])
        return query
    except (ValueError, TypeError, KeyError, UnicodeError):
        raise ValueError('Invalid browser sign-in prompt') from None


def validate_callback(prompt, address):
    try:
        if not isinstance(address, str) or not 1 <= len(address) <= 4000:
            raise ValueError()
        address = address.strip()
        expected = browser_parameters(prompt)
        uri = urlparse(address)
        if address.split('?')[0] != expected['redirect_uri'] or '#' in address or any(ord(c) <= 32 or ord(c) >= 127 or c in '<>"{}|\\^`' for c in address):
            raise ValueError()
        query = unique_query(uri.query)
        if (not hmac.compare_digest(query.get('state', ''), expected['state'])
                or ('code' in query) == ('error' in query)
                or not query.get('code', query.get('error', '')).strip()):
            raise ValueError()
        return address
    except (ValueError, TypeError, KeyError, UnicodeError):
        raise ValueError('That address does not match this sign-in. Copy the entire final localhost callback address from the current Microsoft sign-in tab.') from None


def validate_snapshot(value, run_id):
    if not isinstance(value, dict) or set(value) - SNAPSHOT_KEYS:
        raise ValueError('Unknown snapshot fields')
    if identifier(value.get('runId')) != run_id:
        raise ValueError('Run mismatch')
    context = value.get('context', '')
    if context:
        identifier(context)
    if not isinstance(context, str) or value.get('state') not in STATES:
        raise ValueError('Invalid state')
    if not isinstance(value.get('username'), str) or not re.fullmatch(r'[A-Za-z0-9_]{1,16}', value['username']):
        raise ValueError('Invalid username')
    identifier(value.get('accountId'))
    if type(value.get('canLogin')) is not bool or not isinstance(value.get('message'), str) or len(value['message']) > 512:
        raise ValueError('Invalid status')
    if 'canTest' in value and type(value['canTest']) is not bool:
        raise ValueError('Invalid test capability')
    prompt = value.get('prompt')
    if prompt is not None:
        if not isinstance(prompt, dict) or set(prompt) != {'userCode', 'verificationUri', 'expiresAt'}:
            raise ValueError('Invalid device prompt fields')
        uri = urlparse(prompt['verificationUri'])
        if (uri.scheme != 'https' or uri.hostname not in {'microsoft.com', 'www.microsoft.com', 'login.microsoftonline.com', 'login.live.com'}
                or uri.username or uri.password or uri.port is not None
                or not isinstance(prompt['userCode'], str) or not re.fullmatch(r'[A-Za-z0-9-]{4,32}', prompt['userCode'])
                or type(prompt['expiresAt']) is not int):
            raise ValueError('Invalid device prompt')
        if value['state'] != 'signing_in':
            raise ValueError('Prompt outside sign-in')
    browser = value.get('browserPrompt')
    if browser is not None:
        browser_parameters(browser)
        if value['state'] != 'signing_in' or prompt is not None:
            raise ValueError('Browser prompt outside sign-in or mixed with a device prompt')
    return dict(value)


@dataclass
class Instance:
    id: str
    label: str
    secret: str = field(repr=False)
    login_email: str = field(default='', repr=False)
    snapshot: dict | None = field(default=None, repr=False)
    seen: float = float('-inf')
    command: dict | None = field(default=None, repr=False)
    command_started: float = 0


class Registry:
    def __init__(self, config, clock=time.monotonic, wall=time.time, state_path: Path | None = None, config_path: Path | None = None):
        self.owner = int(config['ownerId'])
        self.instances = {i['id']: Instance(i['id'], i['label'], i['secret'], login_email=i.get('loginEmail', '')) for i in config['instances']}
        self.clock, self.wall, self.state_path = clock, wall, state_path
        self.config_path = config_path
        self.messages = {}
        if state_path and state_path.exists():
            if state_path.is_symlink():
                raise ValueError('State file must not be a symlink')
            self.messages = json.loads(state_path.read_text())

    def authenticate(self, instance_id, authorization):
        instance = self.instances.get(instance_id)
        if instance is None or not hmac.compare_digest('Bearer ' + instance.secret, authorization):
            raise PermissionError('Unauthorized instance')
        return instance

    def online(self, instance):
        return instance.snapshot is not None and self.clock() - instance.seen < 45

    def resolve_instance(self, value):
        key = value.strip()
        instance = self.instances.get(key.lower())
        if instance is not None:
            return instance
        matches = [i for i in self.instances.values() if i.label.casefold() == key.casefold()]
        if len(matches) == 1:
            return matches[0]
        if matches:
            raise ValueError('Multiple instances have that label. Select an autocomplete entry or copy its ID from /sxp status.')
        raise ValueError('Unknown instance. Select an autocomplete entry, type its exact label, or copy its ID from /sxp status.')

    def set_login_email(self, owner, instance_id, value):
        if owner != self.owner:
            raise PermissionError('Only the configured owner may update account hints')
        email = login_email(value)
        instance = self.instances.get(instance_id)
        if instance is None:
            raise ValueError('Unknown instance')
        if self.config_path is None:
            raise ValueError('Configuration path unavailable; update the companion startup command')
        # Read the protected file afresh so editing one hint preserves all other configuration.
        config = load(self.config_path)
        if int(config['ownerId']) != self.owner:
            raise ValueError('Configuration owner changed; restart the companion')
        item = next((i for i in config['instances'] if i['id'] == instance.id), None)
        if item is None:
            raise ValueError('Instance configuration changed; restart the companion')
        if email:
            item['loginEmail'] = email
        else:
            item.pop('loginEmail', None)
        private_write(self.config_path, config)
        instance.login_email = email

    def exchange(self, instance, body):
        if not isinstance(body, dict) or set(body) - {'runId', 'ack', 'snapshot'}:
            raise ValueError('Invalid exchange')
        run_id = identifier(body.get('runId'))
        if self.online(instance) and instance.snapshot['runId'] != run_id:
            raise RuntimeError('Another process is using this instance identity')
        if 'snapshot' in body:
            snapshot = validate_snapshot(body['snapshot'], run_id)
            old = instance.snapshot
            if old is None or (old['runId'], old['context'], old['accountId']) != (run_id, snapshot['context'], snapshot['accountId']):
                instance.command = None
            instance.snapshot = snapshot
            instance.seen = self.clock()
        elif instance.snapshot is None or instance.snapshot['runId'] != run_id:
            return {'resync': True}
        else:
            # Polling alone cannot keep stale game-thread state alive indefinitely.
            pass
        if instance.command and body.get('ack') == instance.command['id']:
            instance.command = None
        self.expire_command(instance)
        return {'command': instance.command}

    def expire_command(self, instance):
        pending = instance.command
        if not pending:
            return
        snapshot = instance.snapshot or {}
        prompt = snapshot.get('browserPrompt')
        if (self.clock() - instance.command_started >= 60 or not self.online(instance)
                or (pending['action'] == 'callback' and (snapshot.get('state') != 'signing_in'
                    or not prompt or prompt['expiresAt'] <= self.wall() * 1000))):
            instance.command = None

    def callback_request(self, owner, instance_id, context):
        if owner != self.owner:
            raise PermissionError('Only the configured owner may control sign-in')
        instance = self.instances.get(instance_id)
        if instance is None or not self.online(instance):
            raise ValueError('Instance is offline; check its PC')
        snapshot = instance.snapshot
        prompt = snapshot.get('browserPrompt')
        if (not context or context != snapshot['context'] or snapshot['state'] != 'signing_in'
                or not prompt or prompt['expiresAt'] <= self.wall() * 1000):
            raise ValueError('This browser sign-in expired or finished. Cancel and start a new sign-in if needed.')
        return prompt

    def command(self, owner, instance_id, context, action, callback=None):
        if owner != self.owner:
            raise PermissionError('Only the configured owner may control sign-in')
        instance = self.instances.get(instance_id)
        if instance is None:
            raise ValueError('Unknown instance. Select an autocomplete entry or copy its ID from /sxp status.')
        self.expire_command(instance)
        if not self.online(instance):
            raise ValueError('Instance is offline; check its PC')
        snapshot = instance.snapshot
        if action == 'test' and not snapshot.get('canTest', False):
            raise ValueError('Phone test unavailable. Use mod 1.3.1+, enable remote recovery with Auth Me, and keep the instance connected to Hypixel without another login in progress.')
        if not context and not snapshot['context']:
            raise ValueError('No authentication recovery is pending. Use /sxp test to test phone sign-in remotely (mod 1.3.1+).')
        if not context or context != snapshot['context']:
            raise ValueError('This button is stale; use /sxp status for the current request')
        if action == 'test':
            if snapshot['state'] not in {'idle', 'restored', 'signed_in', 'paired', 'failed'}:
                raise ValueError('Finish or close the current sign-in request before starting a phone test')
        elif action == 'login':
            if not snapshot['canLogin'] or snapshot['state'] not in {'needs_login', 'cancelled'}:
                raise ValueError('This instance is not waiting for sign-in. Use /sxp test for a remote phone test.')
        elif action == 'callback':
            callback = validate_callback(self.callback_request(owner, instance_id, context), callback)
        elif action == 'cancel':
            if snapshot['state'] != 'signing_in':
                raise ValueError('There is no phone sign-in to cancel')
        else:
            raise ValueError('Unknown command')
        if action != 'callback' and callback is not None:
            raise ValueError('Unexpected callback data')
        if action == 'cancel' and instance.command and instance.command['action'] == 'callback':
            instance.command = None
        if instance.command and self.clock() - instance.command_started < 60:
            if instance.command['action'] == action and instance.command.get('callback') == callback:
                return instance.command
            raise ValueError('Another action is still being delivered')
        instance.command = {'id': str(uuid.uuid4()), 'runId': snapshot['runId'], 'context': context,
                            'action': action, 'expiresAt': int((self.wall() + 60) * 1000)}
        if callback is not None:
            instance.command['callback'] = callback
        instance.command_started = self.clock()
        return instance.command

    def remember_message(self, instance_id, message_id, notified_context):
        snapshot = self.instances[instance_id].snapshot
        self.messages[instance_id] = {'messageId': message_id, 'context': snapshot['context'], 'notifiedContext': notified_context}
        if self.state_path:
            private_write(self.state_path, self.messages)
