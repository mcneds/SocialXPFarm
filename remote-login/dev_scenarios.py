"""Run the phone workflow locally with fake Discord and two synthetic instances.
No bot token, Microsoft account, Hypixel connection, or external network is used.
"""
import asyncio
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).parent / 'tests'))
from helpers import config, snapshot, publish, Clock, FakeDiscord, INSTANCE_A, INSTANCE_B, CONTEXT_A, OWNER
from sxp_remote.core import Registry
from sxp_remote.notifications import Notifications


async def main():
    clock, delivery = Clock(), FakeDiscord()
    registry = Registry(config(), clock, clock)
    notifications = Notifications(registry, delivery, clock)
    for instance, username in [(INSTANCE_A, 'AltA'), (INSTANCE_B, 'AltB')]:
        publish(registry, instance, snapshot(username=username, accountId=instance))
    await notifications.reconcile()
    assert len(delivery.sent) == 2
    print('PASS: each alt receives one independent needs-login alert')
    first = registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
    assert first == registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
    assert registry.instances[INSTANCE_B].command is None
    print('PASS: repeated button presses queue one command for only Alt A')
    context = str(uuid.uuid4())
    prompt = {'userCode':'TEST-CODE', 'verificationUri':'https://microsoft.com/devicelogin', 'expiresAt':900000}
    publish(registry, value=snapshot('signing_in', context=context, prompt=prompt), ack=first['id'])
    await notifications.reconcile()
    assert delivery.edits[-1][3] == prompt
    for phase in ('signed_in', 'restored'):
        publish(registry, value=snapshot(phase, context=context))
        await notifications.reconcile()
    assert delivery.edits[-1][3] is None
    print('PASS: phone code is replaced with signed-in and destination-restored status')
    try:
        registry.command(OWNER, INSTANCE_A, CONTEXT_A, 'login')
        raise AssertionError('Stale button accepted')
    except ValueError:
        pass
    clock.advance(46)
    await notifications.reconcile()
    assert not registry.online(registry.instances[INSTANCE_B])
    print('PASS: stale buttons and offline instances cannot initiate authentication')
    print('Developer scenarios complete. No real sign-ins or messages were sent.')


if __name__ == '__main__':
    asyncio.run(main())
