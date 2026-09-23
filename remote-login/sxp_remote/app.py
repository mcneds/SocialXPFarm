import asyncio
import contextlib
import logging

import discord
from discord import app_commands
from aiohttp import web

from .core import Registry
from .notifications import Notifications, MissingMessage

LOG = logging.getLogger('sxp_remote')


def make_http(registry):
    app = web.Application(client_max_size=16_384)

    async def exchange(request):
        # A browser cannot use this endpoint via cross-origin forms or a DNS-rebinding hostname.
        if request.headers.get('Origin') or request.host.split(':')[0] != '127.0.0.1':
            return web.json_response({'error': 'Forbidden'}, status=403)
        try:
            instance = registry.authenticate(request.match_info['instance'], request.headers.get('Authorization', ''))
            result = registry.exchange(instance, await request.json())
            return web.json_response(result, headers={'Cache-Control': 'no-store'})
        except PermissionError:
            return web.json_response({'error': 'Unauthorized'}, status=401)
        except RuntimeError:
            return web.json_response({'error': 'Instance already active'}, status=409)
        except (ValueError, TypeError, KeyError, AttributeError):
            return web.json_response({'error': 'Invalid request'}, status=400)

    app.router.add_post('/v1/instances/{instance}/exchange', exchange)
    return app


def authorized(registry, interaction):
    return interaction.guild is None and interaction.user.id == registry.owner


class Controls(discord.ui.View):
    def __init__(self, bot, instance_id, context, login=True, cancel=False):
        super().__init__(timeout=None)
        for action, label, enabled in [('login', 'Sign in', login), ('cancel', 'Cancel', cancel)]:
            button = discord.ui.Button(label=label, custom_id=f'sxp:{action}:{instance_id}:{context}',
                                       style=discord.ButtonStyle.primary if action == 'login' else discord.ButtonStyle.secondary,
                                       disabled=not enabled)

            async def callback(interaction, selected=action):
                await bot.action(interaction, instance_id, context, selected)

            button.callback = callback
            self.add_item(button)


class Delivery:
    def __init__(self, bot):
        self.bot = bot
        self.channel = None

    async def dm(self):
        if self.channel is None:
            user = await self.bot.fetch_user(self.bot.registry.owner)
            self.channel = await user.create_dm()
        return self.channel

    def render(self, instance, online, prompt):
        state = instance.snapshot or {}
        name = discord.utils.escape_markdown(instance.label)
        username = discord.utils.escape_markdown(state.get('username', 'unknown'))
        text = f'**{name}** — Minecraft account **{username}**\n'
        phase = state.get('state') if online else 'offline'
        text += {
            'offline': 'Instance unavailable. Check its PC before signing in.',
            'idle': 'No pending authentication request.',
            'disabled': 'Automation disabled. Remote sign-in is unavailable.',
            'renewing': 'Renewing the session automatically.',
            'needs_login': 'Microsoft sign-in is required. Tap Sign in when ready.',
            'signing_in': 'Preparing or checking your phone sign-in.',
            'signed_in': 'Signed in; reconnecting.',
            'paired': 'Phone sign-in verified; automatic renewal saved. Your current game connection was kept.',
            'restored': 'Destination restored.',
            'cancelled': 'Phone sign-in cancelled. You may request a new code.',
            'failed': 'Authentication setup or session installation failed. Check the instance log; desktop sign-in remains available.'
        }.get(phase, 'Status unavailable.')
        view = None
        if online and state.get('context'):
            view = Controls(self.bot, instance.id, state['context'], state.get('canLogin', False), phase == 'signing_in')
        if prompt:
            text += (f"\nOpen {prompt['verificationUri']} and enter **`{prompt['userCode']}`**."
                     f"\nExpires <t:{prompt['expiresAt'] // 1000}:R>. Select **{username}**'s Microsoft account."
                     '\nEnter your password only on Microsoft’s website. Never send it to this bot.')
        return text, view

    async def send(self, instance, online, prompt):
        text, view = self.render(instance, online, prompt)
        message = await (await self.dm()).send(text, view=view, allowed_mentions=discord.AllowedMentions.none())
        return message.id

    async def edit(self, message_id, instance, online, prompt):
        text, view = self.render(instance, online, prompt)
        try:
            await (await self.dm()).get_partial_message(message_id).edit(content=text, view=view, allowed_mentions=discord.AllowedMentions.none())
        except discord.NotFound:
            raise MissingMessage() from None

    async def retire(self, message_id):
        try:
            await (await self.dm()).get_partial_message(message_id).edit(content='This sign-in request is no longer active.', view=None)
        except discord.NotFound:
            raise MissingMessage() from None

    def unavailable(self):
        LOG.warning('Discord notification delivery unavailable; retrying in 60 seconds. Check DM permissions/connectivity.')


class Bot(discord.Client):
    def __init__(self, registry):
        super().__init__(intents=discord.Intents.none())
        self.registry = registry
        self.tree = app_commands.CommandTree(self)
        self.notifier = Notifications(registry, Delivery(self))
        group = app_commands.Group(name='sxp', description='Private SocialXPFarm account recovery')

        @group.command(name='status', description='Show your registered instances')
        async def status(interaction: discord.Interaction):
            if not authorized(registry, interaction):
                await interaction.response.send_message('Use this bot in a direct message from the configured owner account.', ephemeral=True)
                return
            lines = []
            for instance in registry.instances.values():
                state = instance.snapshot or {}
                phase = state.get('state', 'offline') if registry.online(instance) else 'offline'
                test_ready = ' (phone test available)' if registry.online(instance) and state.get('canTest', False) else ''
                lines.append(f"**{discord.utils.escape_markdown(instance.label)}**: {phase}{test_ready} — `{instance.id}`")
            await interaction.response.send_message('\n'.join(lines)[:1900] or 'No instances registered.', allowed_mentions=discord.AllowedMentions.none())

        @group.command(name='login', description='Start phone sign-in for an instance that needs it')
        async def login(interaction: discord.Interaction, instance: str):
            await self.named_action(interaction, instance, 'login')

        @group.command(name='cancel', description='Cancel a pending phone sign-in')
        async def cancel(interaction: discord.Interaction, instance: str):
            await self.named_action(interaction, instance, 'cancel')

        @group.command(name='test', description='Test real phone sign-in while keeping the current game connection')
        async def test(interaction: discord.Interaction, instance: str):
            await self.named_action(interaction, instance, 'test')

        async def complete(interaction, current):
            if not authorized(registry, interaction):
                return []
            return [app_commands.Choice(name=f'{i.label} ({i.id[:8]})', value=i.id) for i in registry.instances.values()
                    if current.lower() in i.label.lower()][:25]

        login.autocomplete('instance')(complete)
        cancel.autocomplete('instance')(complete)
        test.autocomplete('instance')(complete)
        self.tree.add_command(group)
        self.notification_task = None

    async def setup_hook(self):
        for instance_id, meta in self.registry.messages.items():
            if instance_id in self.registry.instances and meta.get('context'):
                self.add_view(Controls(self, instance_id, meta['context']), message_id=meta['messageId'])
        await self.tree.sync()
        self.notification_task = asyncio.create_task(self.notifications())

    async def notifications(self):
        await self.wait_until_ready()
        while not self.is_closed():
            await self.notifier.reconcile()
            await asyncio.sleep(2)

    async def named_action(self, interaction, instance_id, action):
        if not authorized(self.registry, interaction):
            await self.action(interaction, instance_id, '', action)
            return
        try:
            instance = self.registry.resolve_instance(instance_id)
        except ValueError as error:
            await interaction.response.send_message(str(error), ephemeral=True)
            return
        context = (instance.snapshot or {}).get('context', '')
        await self.action(interaction, instance.id, context, action)

    async def action(self, interaction, instance_id, context, action):
        if not authorized(self.registry, interaction):
            await interaction.response.send_message('Only the configured owner can use these controls in a DM.', ephemeral=True)
            return
        await interaction.response.defer(thinking=True)
        try:
            self.registry.command(interaction.user.id, instance_id, context, action)
            message = 'Request queued. The instance will update its sign-in message shortly.'
        except (ValueError, PermissionError) as error:
            message = str(error)  # Fixed local messages, never upstream response bodies.
        await interaction.followup.send(message, allowed_mentions=discord.AllowedMentions.none())

    async def on_error(self, *args, **kwargs):
        LOG.error('Discord event failed; check connectivity and configuration. Event payload omitted.')

    async def close(self):
        if self.notification_task:
            self.notification_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.notification_task
        await super().close()


async def serve(config, state_path):
    registry = Registry(config, state_path=state_path)
    runner = web.AppRunner(make_http(registry), access_log=None)
    await runner.setup()
    bot = Bot(registry)
    try:
        await web.TCPSite(runner, '127.0.0.1', config['port']).start()
        async with bot:
            await bot.start(config['botToken'])
    finally:
        await runner.cleanup()


def run(config, state_path):
    logging.basicConfig(level=logging.WARNING)
    try:
        asyncio.run(serve(config, state_path))
    except KeyboardInterrupt:
        pass
    except Exception:
        LOG.error('Companion stopped. Check bot credentials, local port and configuration. Sensitive details omitted.')
        raise SystemExit(1) from None
