# Phone sign-in with Discord

This companion runs on the **same Linux PC as your Minecraft instances**. It sends private Discord DMs when an instance needs Microsoft sign-in. You tap **Sign in**, enter the short code on Microsoft's website from your phone, and the instance resumes recovery after verifying the account.

Normal refresh-token renewal stays automatic. The companion receives only status and the short user-facing code. Microsoft passwords, private device codes, access tokens, and refresh tokens remain outside Discord and the companion.

## Install

Use SocialXPFarm **1.3.0 or newer**, Auth Me **9.3.0+26.2**, and Python **3.12–3.14**. Keep only one enabled mod jar per instance and restart when updating.

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
- Open the Microsoft verification link, enter the code, and choose that alt's Microsoft account. Selecting another account is rejected before credentials are saved or installed.
- The DM updates to **Signed in; reconnecting**, then **Destination restored** when the existing island check succeeds.
- Use **Cancel** or **`/sxp cancel instance`** to cancel an active phone login. Codes that expire or are declined require another deliberate click; the bot does not keep generating codes while you are busy.

Remote sign-in is available only for a current pending authentication recovery. It does not change accounts in a healthy connected instance or enable disabled automation. `/sxp auth login` inside Minecraft still provides desktop setup. With remote mode configured, rejected sessions wait for your Discord action instead of launching the PC browser automatically.

F8 or `/sxp off` cancels automatic/remote recovery, suppresses new recovery alerts, and leaves status reporting available. Saved login credentials remain available for later use. `/sxp auth forget` removes the Minecraft refresh credential; disabling/removing remote integration is separate: set `enabled` to `false` in that instance's `remote.json` and restart it.

## Start automatically on Linux

After setup works manually, run these commands from this directory using its virtual environment:

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

## Recovery and troubleshooting

- **No notification:** check that automation, a configured destination, and `autoReconnect` are enabled; the bot is running; DMs are allowed; and `/sxp status` shows the instance online. Ordinary network errors continue normal retries and do not request phone login.
- **Companion unavailable:** existing local reconnection and silent token renewal continue. Pending remote login waits for the companion to return. Desktop `/sxp auth login` and Auth Me's Re-Login remain available.
- **Offline status:** heartbeat snapshots publish on change and at least every 15 seconds; after 45 seconds without a fresh snapshot, controls reject actions. Running a duplicate instance with the same identity is rejected. A restarted process may need to wait up to 45 seconds for the old registration to expire.
- **Stale button:** use `/sxp status` and the current request. Old buttons cannot target a different process, authentication attempt, or account. Commands also expire after 60 seconds.
- **Unsupported OAuth client:** remote login fails visibly, preserving desktop login. The default Auth Me registration was checked on 2026-09-23: the device endpoint issued a code and token polling returned `authorization_pending`. This did not complete a real account sign-in or verify Xbox/Minecraft access.
- **Revoked credentials or MFA:** complete Microsoft's legitimate sign-in from your phone; the mod cannot bypass it.

Notification identifiers are saved locally for restart reconciliation; codes are held only in memory and removed from the current message after expiry/completion. Discord itself can retain messages/history, so user codes must be treated as short-lived sensitive information. The bot never receives your Microsoft password or reusable Microsoft tokens. Delivery retries after 60 seconds when DMs/network are unavailable. A crash precisely between sending a DM and saving its identifier can produce a duplicate alert, but cannot duplicate an accepted login operation.

Live Discord delivery and a complete Microsoft → Minecraft → Hypixel phone login still require validation with your bot and accounts. See [developer tests and scenarios](DEVELOPMENT.md).
