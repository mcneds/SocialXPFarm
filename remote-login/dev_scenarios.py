"""Run the phone workflow locally with fake Discord and two synthetic instances.
No bot token, Microsoft account, Hypixel connection, or external network is used.
"""
import asyncio
from pathlib import Path
import sys
import uuid

sys.path.insert(0, str(Path(__file__).parent / 'tests'))
from helpers import config, snapshot, publish, Clock, FakeDiscord, INSTANCE_A, INSTANCE_B, CONTEXT_A, OWNER
from sxp_remote.core import Registry, browser_parameters
import json
from urllib.parse import urlencode
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
    context = str(uuid.uuid4())
    publish(registry, value=snapshot('idle', context=context, canTest=True))
    command = registry.command(OWNER, INSTANCE_A, context, 'test')
    assert command['action'] == 'test'
    assert registry.instances[INSTANCE_B].command is None
    context = str(uuid.uuid4())
    publish(registry, value=snapshot(context=context), ack=command['id'])
    await notifications.reconcile()
    registry.command(OWNER, INSTANCE_A, context, 'login')
    context = str(uuid.uuid4())
    browser = json.loads((Path(__file__).parent / 'tests/fixtures/browser-snapshot.json').read_text())['browserPrompt']
    browser['expiresAt'] = int((clock() + 300) * 1000)
    publish(registry, value=snapshot('signing_in', context=context, browserPrompt=browser))
    params = browser_parameters(browser)
    callback = params['redirect_uri'] + '?' + urlencode({'state': params['state'], 'code': 'synthetic-callback'})
    try:
        registry.command(OWNER, INSTANCE_B, context, 'callback', callback)
        raise AssertionError('Cross-instance callback accepted')
    except ValueError:
        pass
    submitted = registry.command(OWNER, INSTANCE_A, context, 'callback', callback)
    assert submitted == registry.command(OWNER, INSTANCE_A, context, 'callback', callback)
    assert registry.exchange(registry.instances[INSTANCE_A], {'runId': submitted['runId']})['command'] == submitted
    registry.exchange(registry.instances[INSTANCE_A], {'runId': submitted['runId'], 'ack': submitted['id']})
    assert registry.instances[INSTANCE_A].command is None
    print('PASS: browser account picker callback routes once to its intended instance and clears on acknowledgement')
    await notifications.reconcile()
    publish(registry, value=snapshot('paired', context=context, canTest=True))
    await notifications.reconcile()
    assert delivery.edits[-1][3] is None
    assert registry.instances[INSTANCE_A].snapshot['state'] == 'paired'
    print('PASS: Discord test starts from idle and finishes paired without claiming reconnection')
    print('Developer scenarios complete. No real sign-ins or messages were sent.')


if __name__ == '__main__':
    asyncio.run(main())
