"""Pure protocol/state machine; no Discord SDK and no Microsoft credentials."""
from dataclasses import dataclass, field
import hmac
import json
from pathlib import Path
import re
import time
from urllib.parse import urlparse
import uuid

from .config import private_write

STATES = {'idle', 'disabled', 'renewing', 'needs_login', 'signing_in', 'signed_in', 'restored', 'cancelled', 'failed'}
SNAPSHOT_KEYS = {'runId', 'context', 'username', 'accountId', 'state', 'message', 'canLogin', 'prompt'}


def identifier(value):
    if not isinstance(value, str):
        raise ValueError('Invalid identifier')
    return str(uuid.UUID(value))


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
    return dict(value)


@dataclass
class Instance:
    id: str
    label: str
    secret: str = field(repr=False)
    snapshot: dict | None = field(default=None, repr=False)
    seen: float = float('-inf')
    command: dict | None = field(default=None, repr=False)
    command_started: float = 0


class Registry:
    def __init__(self, config, clock=time.monotonic, wall=time.time, state_path: Path | None = None):
        self.owner = int(config['ownerId'])
        self.instances = {i['id']: Instance(i['id'], i['label'], i['secret']) for i in config['instances']}
        self.clock, self.wall, self.state_path = clock, wall, state_path
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
        pending = instance.command
        if pending and (body.get('ack') == pending['id'] or self.clock() - instance.command_started >= 60 or not self.online(instance)):
            instance.command = None
        return {'command': instance.command}

    def command(self, owner, instance_id, context, action):
        if owner != self.owner:
            raise PermissionError('Only the configured owner may control sign-in')
        instance = self.instances.get(instance_id)
        if not instance or not self.online(instance):
            raise ValueError('Instance is offline; check its PC')
        snapshot = instance.snapshot
        if not context and not snapshot['context']:
            raise ValueError('No authentication recovery is pending. Remote sign-in becomes available when this instance requires Microsoft sign-in.')
        if not context or context != snapshot['context']:
            raise ValueError('This button is stale; use /sxp status for the current request')
        if action == 'login':
            if not snapshot['canLogin'] or snapshot['state'] not in {'needs_login', 'cancelled'}:
                raise ValueError('This instance is not waiting for sign-in')
        elif action == 'cancel':
            if snapshot['state'] != 'signing_in':
                raise ValueError('There is no phone sign-in to cancel')
        else:
            raise ValueError('Unknown command')
        if instance.command and self.clock() - instance.command_started < 60:
            if instance.command['action'] == action:
                return instance.command
            raise ValueError('Another action is still being delivered')
        instance.command = {'id': str(uuid.uuid4()), 'runId': snapshot['runId'], 'context': context,
                            'action': action, 'expiresAt': int((self.wall() + 60) * 1000)}
        instance.command_started = self.clock()
        return instance.command

    def remember_message(self, instance_id, message_id, notified_context):
        snapshot = self.instances[instance_id].snapshot
        self.messages[instance_id] = {'messageId': message_id, 'context': snapshot['context'], 'notifiedContext': notified_context}
        if self.state_path:
            private_write(self.state_path, self.messages)
