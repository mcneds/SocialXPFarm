import copy
import uuid

INSTANCE_A = str(uuid.UUID(int=1))
INSTANCE_B = str(uuid.UUID(int=2))
RUN_A = str(uuid.UUID(int=3))
CONTEXT_A = str(uuid.UUID(int=4))
OWNER = 123456789012345678
SECRET_A = 'synthetic-a-' + 'a' * 32
SECRET_B = 'synthetic-b-' + 'b' * 32


def config():
    return {'ownerId': str(OWNER), 'botToken': 'synthetic-bot-token', 'port': 38471, 'instances': [
        {'id': INSTANCE_A, 'label': 'Alt A', 'secret': SECRET_A},
        {'id': INSTANCE_B, 'label': 'Alt B', 'secret': SECRET_B}]}


def snapshot(state='needs_login', context=CONTEXT_A, run=RUN_A, **changes):
    value = {'runId': run, 'context': context, 'username': 'AltA', 'accountId': INSTANCE_A,
             'state': state, 'message': 'Sign in once.', 'canLogin': state in {'needs_login', 'cancelled'}}
    value.update(changes)
    return value


def publish(registry, instance=INSTANCE_A, value=None, ack=''):
    value = value or snapshot()
    return registry.exchange(registry.instances[instance], {'runId': value['runId'], 'snapshot': value, 'ack': ack})


class Clock:
    def __init__(self):
        self.now = 0
    def __call__(self):
        return self.now
    def advance(self, seconds):
        self.now += seconds


class FakeDiscord:
    """Captures display data in memory; it never opens a network connection."""
    def __init__(self):
        self.sent, self.edits, self.retired = [], [], []
        self.blocked, self.missing = False, False
        self.failures = 0

    async def send(self, instance, online, prompt):
        if self.blocked:
            raise ConnectionError('synthetic Discord outage')
        self.sent.append((instance.id, online, copy.deepcopy(prompt)))
        return 100 + len(self.sent)

    async def edit(self, message_id, instance, online, prompt):
        if self.blocked:
            raise ConnectionError('synthetic Discord outage')
        if self.missing:
            from sxp_remote.notifications import MissingMessage
            self.missing = False
            raise MissingMessage()
        self.edits.append((message_id, instance.id, online, copy.deepcopy(prompt)))

    async def retire(self, message_id):
        self.retired.append(message_id)

    def unavailable(self):
        self.failures += 1
