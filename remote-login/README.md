# Phone sign-in with Discord

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

This companion runs on the **same Linux PC as your Minecraft instances**. It sends private Discord DMs when an instance needs Microsoft sign-in. You tap **Sign in**, choose the correct Microsoft account in the browser, and paste the final callback address into a private Discord form. The instance resumes recovery after verifying the account.

Normal refresh-token renewal stays automatic. The form passes a short-lived, single-use authorization code through Discord and the local companion. The PKCE verifier, access tokens, and refresh tokens stay on the Minecraft instance; passwords are entered only on Microsoft’s website. Callback addresses are never written to the companion’s configuration/state files or echoed in chat. Discord processes form submissions, so treat the callback as sensitive and submit it only to your own bot.

## Optional browser-only sign-in

The **1.4.0 candidate** supports a registered HTTPS callback through Cloudflare Tunnel, so a phone can finish sign-in without copying a localhost address. This is opt-in and requires a Microsoft application you control. Follow the [browser-only setup and compatibility check](BROWSER-LOGIN.md); verify a full Minecraft login on one instance before migration. Existing desktop, device-code and callback-form flows remain available.

## Install

Use SocialXPFarm **1.3.2 or newer**, Auth Me **9.3.0+26.2**, and Python **3.12–3.14**. Keep only one enabled mod jar per instance and restart when updating. Update the companion alongside the mod.

Download and extract `SocialXPFarm-remote-login.zip` from the same release as the mod, or use this directory from the repository. In this directory:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock
```

On Debian/Ubuntu, install the matching `python3-venv` package first if creation reports that `ensurepip` is unavailable. The companion currently supports Linux/POSIX permission handling; it does not require a VPN or router port forwarding.

## Create your Discord bot

1. Create an application and bot in the [Discord Developer Portal](https://discord.com/developers/applications). Copy its bot token for the local wizard below. Do not paste the token into chat, source files, screenshots, or a shell command.
2. Install the bot in a private Discord server you control, using the `bot` and `applications.commands` scopes. It needs no Administrator permission and no privileged intents: leave Message Content, Server Members, and Presence intents off. Leave the **Interactions Endpoint URL empty**; this companion receives interactions through Discord's outbound Gateway connection.
3. Enable Discord Developer Mode and copy **your user ID**, not a server or channel ID. Only this user can operate the bot, and only in a direct message.
4. Allow DMs from the bot/shared server and enable Discord mobile push notifications. Discord's desktop activity and notification settings can affect when your phone alerts you.

## Configure instances locally

```bash
.venv/bin/python -m sxp_remote setup
```

The wizard asks for the bot token with input hidden, your Discord user ID, and each instance's **game directory** containing `mods/` and `config/`. For Prism this is usually the instance's `.minecraft` directory. Give each a recognizable label such as `Alt A` or `Alt B`.

It writes:

- Companion configuration: `~/.config/socialxpfarm-remote/config.json`, containing the bot token and registered instance secrets.
- Each instance: `config/socialxpfarm-auth/remote.json`, containing only that instance's ID/secret, the local companion port, and OAuth client ID.

Directories/files are restricted to owner-only access (700/600). Existing Minecraft refresh credentials stay in that instance's `account.json`. Rerunning setup preserves registered instance IDs/secrets and allows you to add instances or rename their labels. All instances must use different game directories. Do not share/export these credential directories.

Restart the registered Minecraft instances, then start the companion:

```bash
.venv/bin/python -m sxp_remote serve
```

The default local endpoint is `127.0.0.1:38471`. If it is occupied, stop the conflicting process or change the companion `port`, rerun setup to update the instance files, and restart both sides. Do not bind or forward this endpoint to the internet.

## Use it from your phone

- DM the bot **`/sxp status`** to see registered instances. Slash commands may take time to become visible after initial registration.
- When an enabled instance cannot renew silently, it sends a DM identifying its label and expected Minecraft username. Tap **Sign in** when ready, or use **`/sxp login instance`** with autocomplete.
- Open **Choose Microsoft account** and select that alt's Microsoft account, using **Use another account** if necessary. The flow requests Microsoft's account picker, like Auth Me's desktop login.
- On your phone, the final `http://localhost:<port>/callback?...` page usually says it cannot connect. This is expected: localhost refers to your phone. Copy the **whole address from the address bar**, tap **Paste callback** in the bot DM, paste it into the form, and submit promptly. Do not paste it as a chat message. Some phones require opening the link in their full browser to access the address bar.
- The request lasts five minutes, but the returned authorization code has a much shorter lifetime. If you delay after signing in and it expires, cancel and start a fresh sign-in. On the Minecraft PC, the localhost callback can finish automatically.
- The instance validates the callback state, PKCE and Minecraft UUID. Choosing another account is rejected before credentials are saved or installed.
- The DM updates to **Signed in; reconnecting**, then **Destination restored** when the existing island check succeeds.
- Use **Cancel** or **`/sxp cancel instance`** to cancel an active phone login. Codes that expire or are declined require another deliberate click; the bot does not keep generating codes while you are busy.

### Identify the right Microsoft account

In the bot DM, use **`/sxp email`**, select an instance, and enter its Microsoft sign-in email in the `address` field. For example: `/sxp email instance:main address:alt@example.com`. Omit `address` to view its current hint, or enter `clear` to remove it. Only the configured owner can use this command, only in DMs. Instances can be offline while you configure their hints.

The bot shows this email next to the Minecraft username in sign-in messages, including already-open requests. Hints persist in the companion's owner-only configuration as each instance's optional `loginEmail` field; no restart is needed when using the Discord command. You can also edit that field locally and restart the companion. Emails are user-configured reminders, not automatically discovered or verified Microsoft identities. Update the hint if you change an instance's account. The Minecraft UUID check still rejects another account before saving its credentials.

Version **1.3.2** uses the [authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow) with `prompt=select_account` and PKCE. The email is a reminder; the account picker lets you select a different account without relying on the cached device-login page. A Discord link cannot force private browsing or clear cookies.

The updated companion still supports older mods' device-code prompts during upgrades. If you still see **enter a code** rather than **Choose Microsoft account** and **Paste callback**, restart that Minecraft instance with the 1.3.2 jar. Cancel an old request and start a new one after updating.

`/sxp login` handles a current pending authentication request. It does not change accounts in a healthy connected instance or enable disabled automation. `/sxp auth login` inside Minecraft still provides desktop setup. With remote mode configured, rejected sessions wait for your Discord action instead of launching the PC browser automatically.

`idle` in `/sxp status` means the instance is reporting and has no pending authentication recovery; it does not confirm island arrival or XP farming. Using `/sxp login` then points you to `/sxp test`. A “stale” button instead belongs to an authentication request that is no longer current.

### Test sign-in entirely from Discord

1. Update both the companion and the mod to the same release, restart the companion service, and restart each updated Minecraft instance. Old mods cannot accept this test command.
2. Keep the selected instance connected to Hypixel with automation, its destination, `autoReconnect`, Auth Me, and remote integration enabled. `/sxp status` should show **phone test available**.
3. In the bot DM, run **`/sxp test`** and select the instance with autocomplete or type its exact label, such as `main`. Labels ignore case and surrounding spaces. If labels are duplicated, select the autocomplete entry (which shows part of its ID) or paste the full ID from `/sxp status`. No in-game command is needed.
4. Tap **Sign in**, then **Choose Microsoft account**. Select that alt's account. When the browser reaches the localhost callback, copy the entire address and submit it using **Paste callback** in the DM.
5. Expect **Phone sign-in verified; automatic renewal saved** and status **paired**. You can run another test from Discord afterward.

This is a real Microsoft → Xbox → Minecraft account check and saves that instance's refresh credential after its Minecraft UUID matches. It preserves the current game session and connection, so it does **not** test disconnect detection, Auth Me session installation, or reconnect/island recovery. Existing credentials are not removed to begin a test. Wrong-account, declined, or expired attempts can be retried with **Sign in**; **Cancel** closes the pending callback and prevents late credential writes. Disabling automation, changing the active account, disconnecting, or forgetting credentials cancels the pending test. A companion restart preserves an active test in the still-running game instance and reconciles its status.

F8 or `/sxp off` cancels automatic/remote recovery, suppresses new recovery alerts, and leaves status reporting available. Saved login credentials remain available for later use. `/sxp auth forget` removes the Minecraft refresh credential; disabling/removing remote integration is separate: set `enabled` to `false` in that instance's `remote.json` and restart it.

## Start automatically on Linux

After setup works manually, open a terminal in the companion folder containing `sxp_remote/` and `.venv/`—the same folder where you ran `serve`. For example, if you extracted the release into `~/Code/SocialXPFarm-remote-login`, run `cd ~/Code/SocialXPFarm-remote-login/remote-login` first. For a repository checkout, use its `remote-login/` folder.

Stop any manually running companion with **Ctrl+C** before starting the service. Then run:

```bash
mkdir -p ~/.config/systemd/user
.venv/bin/python -m sxp_remote service-unit > ~/.config/systemd/user/socialxpfarm-remote.service
systemctl --user daemon-reload
systemctl --user enable --now socialxpfarm-remote.service
```

The generated unit uses the current virtual environment and absolute directory paths; regenerate it if you move the companion. It restarts after failure and reads credentials from the protected configuration file. To continue after logging out, your system may need user lingering enabled (`loginctl enable-linger "$USER"`). This does not start or restart Minecraft itself.

```bash
systemctl --user status socialxpfarm-remote.service
journalctl --user -u socialxpfarm-remote.service
```

After updating the companion, regenerate an existing service from the updated companion folder and restart it:

```bash
.venv/bin/python -m sxp_remote service-unit > ~/.config/systemd/user/socialxpfarm-remote.service
systemctl --user daemon-reload
systemctl --user restart socialxpfarm-remote.service
systemctl --user status socialxpfarm-remote.service --no-pager
```

This also replaces the incorrectly quoted `WorkingDirectory` emitted by the original 1.3.0 companion, which caused a “bad unit file setting” error. No manual service-file editing is needed. Regeneration preserves your bot credentials and instance registrations. Use the same `--config` option when regenerating if you originally selected a custom configuration path.

## Recovery and troubleshooting

- **No notification:** check that automation, a configured destination, and `autoReconnect` are enabled; the bot is running; DMs are allowed; and `/sxp status` shows the instance online. Ordinary network errors continue normal retries and do not request phone login.
- **Companion unavailable:** existing local reconnection and silent token renewal continue. Pending remote login waits for the companion to return. Desktop `/sxp auth login` and Auth Me's Re-Login remain available.
- **Offline status:** heartbeat snapshots publish on change and at least every 15 seconds; after 45 seconds without a fresh snapshot, controls reject actions. Running a duplicate instance with the same identity is rejected. A restarted process may need to wait up to 45 seconds for the old registration to expire.
- **Stale button:** use `/sxp status` and the current request. Old buttons cannot target a different process, authentication attempt, or account. Commands also expire after 60 seconds.
- **Unsupported OAuth client:** remote login fails visibly, preserving desktop login. The default uses Auth Me’s public desktop registration with a localhost redirect and PKCE. A custom registration must support the same native-app authorization-code flow. Automated tests do not establish Microsoft acceptance of a custom registration.
- **Callback rejected:** copy the full address from the current request, including everything after `?`. Each instance and attempt has a different port/state. A copied address from another alt, an expired attempt, or an already-consumed callback cannot complete the current request. Cancel and sign in again if the code expires.
- **Revoked credentials or MFA:** complete Microsoft's legitimate sign-in from your phone; the mod cannot bypass it.

Only notification identifiers are saved locally for restart reconciliation. Callback codes are queued in memory and discarded on acknowledgement, expiry, cancellation, an offline instance, or a changed request; a companion restart discards undelivered callbacks. If necessary, reopen the form and submit again promptly, or start a new login. The account-picker link is removed from the current message after expiry/completion. The bot never receives your Microsoft password or reusable Microsoft tokens. Delivery retries after 60 seconds when DMs/network are unavailable. A crash precisely between sending a DM and saving its identifier can produce a duplicate alert, but cannot duplicate an accepted login operation.

Live Discord delivery and a complete Microsoft → Minecraft → Hypixel phone login still require validation with your bot and accounts. See [developer tests and scenarios](DEVELOPMENT.md).
