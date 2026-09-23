"""Notification reconciliation, independently testable without contacting Discord."""
import time


class MissingMessage(Exception):
    pass


class Notifications:
    def __init__(self, registry, delivery, clock=time.monotonic):
        self.registry, self.delivery, self.clock = registry, delivery, clock
        self.stamps, self.retry_at = {}, {}

    async def reconcile(self):
        for instance in self.registry.instances.values():
            if self.clock() < self.retry_at.get(instance.id, 0):
                continue
            online = self.registry.online(instance)
            snapshot = instance.snapshot or {}
            prompt = snapshot.get('prompt') if online and snapshot.get('state') == 'signing_in' else None
            if prompt and prompt['expiresAt'] <= self.registry.wall() * 1000:
                prompt = None
            stamp = (online, snapshot.get('context'), snapshot.get('state'), snapshot.get('canLogin'),
                     snapshot.get('username'), snapshot.get('accountId'), instance.login_email, tuple(sorted(prompt.items())) if prompt else None)
            meta = self.registry.messages.get(instance.id, {})
            notify = online and snapshot.get('context') and snapshot.get('state') in {'needs_login', 'failed'}
            new_alert = notify and meta.get('notifiedContext') != snapshot['context']
            if not new_alert and (not meta or self.stamps.get(instance.id) == stamp):
                continue
            try:
                if new_alert:
                    if meta.get('messageId'):
                        try:
                            await self.delivery.retire(meta['messageId'])
                        except MissingMessage:
                            pass
                    message_id = await self.delivery.send(instance, online, prompt)
                else:
                    try:
                        await self.delivery.edit(meta['messageId'], instance, online, prompt)
                        message_id = meta['messageId']
                    except MissingMessage:
                        message_id = await self.delivery.send(instance, online, prompt)
                if instance.snapshot:
                    self.registry.remember_message(instance.id, message_id,
                        snapshot['context'] if new_alert else meta.get('notifiedContext', ''))
                self.stamps[instance.id] = stamp
                self.retry_at.pop(instance.id, None)
            except Exception:
                # Blocked DMs, rate limiting, outages: keep unsent state and retry without log payloads.
                self.retry_at[instance.id] = self.clock() + 60
                self.delivery.unavailable()
