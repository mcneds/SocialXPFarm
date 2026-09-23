# SocialXPFarm

Client-side Fabric **26.2** mod with toggleable recovery to your own Hypixel SkyBlock island or a configured player's island.

## Install and start

1. Install Fabric Loader and Fabric API for Minecraft **26.2** using Java **25**.
2. Download `SocialXPFarm-latest.jar` from the [latest build](https://github.com/mcneds/SocialXPFarm/releases/tag/latest-build) and put it in your Minecraft instance's `mods/` folder.
3. Optionally install **Auth Me for Fabric 26.2** and its required dependencies for in-game session renewal.
4. Launch once to create `config/socialxpfarm.json`, then close Minecraft and set `targetPlayer` and `profileName`.
5. Relaunch and connect to Hypixel. Guest recovery starts when both destination values are configured. For your own island instead, enter `/sxp mode own`; guest destination values are not required.

When updating, replace the old SocialXPFarm jar and restart each Minecraft instance. Keep **only one enabled SocialXPFarm jar** in each instance's `mods/` folder: leaving both `SocialXPFarm-latest.jar` and a downloaded `SocialXPFarm-latest(1).jar` can leave the older build selected. Copying a new jar into a running instance does not update its loaded code.

## In-game controls

Press **F8** to toggle all automation. Rebind it under **Options → Controls → Key Binds → SocialXPFarm**.

| Command | Action |
|---|---|
| `/sxp` | Show whether automation is enabled and the selected destination. |
| `/sxp toggle` | Toggle all automation on/off. |
| `/sxp on` | Enable recovery to the selected destination. |
| `/sxp off` | Disable all automation, including commands, menu clicks, reconnects, and Auth Me handoffs. |
| `/sxp mode own` | Recover to your own island using `/is`. |
| `/sxp mode guest` | Recover to the configured player/profile using `/visit`. |
| `/sxp auth` | Show whether this instance has a saved login for its current account. |
| `/sxp auth login` | Pair this instance's Microsoft account once for automatic session renewal. Requires Auth Me. |
| `/sxp auth forget` | Remove this instance's saved login and cancel pending renewal. |

**Enabled state and destination are separate settings.** Switching off cancels pending recovery and leaves you where you are; it does not send you home or disconnect you. Changing the destination preserves the enabled state. For example, `/sxp mode own` followed by `/sxp on` enables own-island recovery. Both settings save immediately and survive a restart. Your configured guest destination is retained when switching modes.

## Recovery behavior

Guest mode treats the `SKYBLOCK GUEST` sidebar as healthy; the island owner does not need to remain online. If guest mode disappears for a few seconds, it rejoins SkyBlock, sends `/visit <targetPlayer>`, and selects a profile in the visit menu. Own-island mode sends `/is` and requires a non-guest SkyBlock sidebar with the `Your Island` location; the Hub and Garden do not count as home. It uses the currently selected SkyBlock profile and does not switch profiles.

| Situation | Default response |
|---|---|
| Ordinary limbo | Send `/lobby`, wait for the lobby, then `/play sb` and revisit. |
| Limbo commands make no progress | Reconnect after two minutes. |
| Kick, timeout, or failed connection | Retry the last attempted Hypixel address with delays starting at 10 seconds and capped at 60 seconds. |
| Login, configuration, or world loading stalls | Abort the stalled stage after two minutes, then reconnect. |
| Detected admission queue | Wait, preserving the connection. Position notices refresh a five-minute timeout; a queue sidebar also pauses recovery. |
| Commands sent too quickly | Pause recovery commands for 20 seconds after a recognized throttle notice. |
| Server full, login throttling, or stale “already connected” state | Wait at least 60 seconds before reconnecting. |
| Visit menu or island transfer fails | Close the stale menu and retry, backing off from 5 to 60 seconds. |
| Invalid or expired session | Automatically renew the paired account, install the new session through Auth Me, and reconnect. Initial pairing or revoked consent needs browser sign-in. |
| Recurring protocol error | Allow three automatic retries, then pause for investigation. |
| Ban, account restriction, incompatible version, or another device logs in | Pause until the underlying issue is resolved. |

Reconnect delays include up to 20% random variation within the configured cap. Thirty seconds continuously at the selected destination resets retry history. Returning to the server list or title screen cancels recovery. Manual disconnects stay disconnected, and connecting to another server clears Hypixel recovery.

## Limits

- Automatic authentication requires one initial sign-in per instance. Revoked/expired refresh credentials or Microsoft security requirements can require another sign-in.
- Queue and custom disconnect messages are detected heuristically. New wording may need an update.
- `SKYBLOCK GUEST` alone does not verify the island owner/profile or prove that the server is progressing. The current visit-menu logic can fall back to the only visitable profile head.
- This mod cannot restart a crashed JVM, wake a sleeping computer, or fix an unavailable server or destination restrictions.
- Builds and automated tests pass; the recovery flows still need live Hypixel validation.

`Connection reset by peer` (including Netty's `recvAddress(..) ... error(-104)`) is treated as a transient disconnect and keeps retrying with backoff. It is not an expired-session error. If that screen stays open without any `Disconnect classified` or `Reconnecting` entries in `logs/latest.log`, check that the current jar is the only enabled copy, restart the instance, and verify that the mod is enabled with a configured destination.

See the [disconnect research and recovery matrix](docs/disconnect-recovery.md) for sources, scenario coverage, and the in-game validation checklist.

## Configuration

On first launch the mod creates:

```text
.minecraft/config/socialxpfarm.json
```

Guest mode starts idle until both destination values are filled in. Own-island mode does not need them. Example:

```json
{
  "enabled": true,
  "destination": "GUEST",
  "targetPlayer": "ExamplePlayer",
  "profileName": "Pineapple",
  "guestExitGraceTicks": 60,
  "menuSettleTicks": 6,
  "visitMenuTimeoutTicks": 120,
  "transferTimeoutTicks": 200,
  "skyBlockJoinTimeoutTicks": 240,
  "retryDelayTicks": 100,
  "lobbyJoinTimeoutTicks": 100,
  "autoReconnect": true,
  "reconnectDelayTicks": 200,
  "maxReconnectDelayTicks": 1200,
  "connectTimeoutTicks": 2400,
  "limboReconnectTicks": 2400,
  "maxRetryDelayTicks": 1200
}
```

`destination` is `GUEST` (the default for existing configs) or `OWN`. `enabled: false` disables all automation. `targetPlayer` is the username used by `/visit`. `profileName` is the profile name shown in the visit GUI (for example `Pineapple`, `Coconut`, etc.).

All timing settings use ticks as units; 20 ticks is approximately one second. Reconnect delays and connection/limbo watchdogs measure monotonic elapsed time, so low menu FPS and system-clock adjustments do not stretch them. In-world menu/command timers use client ticks.

Existing configs use defaults for omitted settings. Set `autoReconnect` to `false` to disable reconnecting and the Auth Me handoff while retaining in-server recovery. In-game control changes apply immediately; restart the client after editing the JSON file manually.

## Automatic login for each alt

Install [Auth Me for Fabric 26.2](https://www.curseforge.com/minecraft/mc-mods/auth-me) and its required dependencies alongside this mod. The integration targets [Auth Me 9.3.0+26.2](https://github.com/axieum/authme/tree/v9.3.0%2B26.2); Auth Me is optional and is not bundled.

Set this up **once per Minecraft instance**:

1. Launch the instance with its intended alt selected in your launcher and join a server.
2. Run **`/sxp auth login`**. The browser opens directly to Microsoft account selection.
3. Choose the Microsoft account that owns that alt and complete sign-in/consent. The Minecraft screen closes after it saves the verified account.
4. Run **`/sxp auth`** to confirm pairing. Keep automation and `autoReconnect` enabled for recovery.

After pairing, an expired-session disconnect triggers background renewal, followed by automatic reconnect and return to the selected island. No login-method clicks or browser sign-in are needed for normal renewals. Each instance uses its own saved refresh token and verifies the original Minecraft UUID before applying a session. Browser cookies for your main account or another alt do not affect silent renewal. Network/service failures retry every 60 seconds. Microsoft can revoke or expire refresh credentials or require interaction; those cases open pairing again. See [Microsoft's refresh-token guidance](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens).

Pairing uses Auth Me's public Microsoft OAuth client registration with PKCE, then Auth Me's session API to rebuild Minecraft's user and profile-key services. An ordinary login through Auth Me's **Re-Login** button does **not** enroll automatic renewal; use `/sxp auth login`. If an unpaired instance first encounters a stale session, SocialXPFarm opens its pairing screen automatically. Without Auth Me, renew through the launcher and reconnect manually.

The refresh token is a login credential, stored **unencrypted** in this instance's `config/socialxpfarm-auth/account.json`, outside the regular config, with owner-only filesystem permissions. No Microsoft password is stored. Keep that directory out of shared instance exports and backups accessible to others. `/sxp auth forget` deletes the local credential; it does not revoke Microsoft's consent. Turning automation off cancels pending recovery and keeps the saved account for later use.

See the [authentication scenarios](docs/authentication-scenarios.md) for test coverage and the live two-alt checklist. Automated tests use synthetic credentials; real Microsoft/Hypixel renewal still needs live validation.

## Sign in remotely from your phone

A shared Discord companion supports instances on one Linux PC. When silent renewal needs your help, it sends a private DM identifying the instance and account. Version **1.3.2** uses Microsoft’s account picker with PKCE, like Auth Me’s desktop flow. Tap **Sign in**, open **Choose Microsoft account**, and select the correct account. On your phone, copy the final localhost callback address—even if the page cannot connect—and submit it promptly using **Paste callback** in the DM. After verifying the Minecraft UUID, the instance reconnects automatically.

Download `SocialXPFarm-remote-login.zip` alongside the mod from the [latest release](https://github.com/mcneds/SocialXPFarm/releases/tag/latest-build). Follow the [companion setup guide](remote-login/README.md) to create a bot, enter credentials locally, and register your instance directories. Only your configured Discord user can use the controls. No VPN or public incoming ports are required. Remote mode stays off until configured.

Use Discord DMs for `/sxp status`, `/sxp login instance`, and `/sxp cancel instance`. With **1.3.1+** and the matching companion, use **`/sxp test instance`** to start a real phone sign-in test remotely while the instance is connected to Hypixel. The test saves verified renewal credentials and keeps the current connection; it does not exercise disconnect/reconnect recovery. These are separate from in-game commands. Remote sign-in does not switch healthy instances between accounts or enable automation remotely. Existing local login and silent refresh remain available.

The callback form carries a short-lived authorization code through Discord; reusable tokens and the PKCE verifier stay on the Minecraft instance. Full phone authentication and recovery still require live validation with your accounts. See the [developer test guide](remote-login/DEVELOPMENT.md) for the automated suites and live acceptance checklist.

Use **`/sxp email instance address`** in the owner-only bot DM to set the Microsoft email reminder displayed for each instance. Sign-in messages show this email beside the Minecraft username and link to Microsoft’s account picker. Email hints do not replace Minecraft UUID verification.

## Recovery verification

`./gradlew build` runs regression tests for Hypixel address scoping, disconnect classification, queue/throttle notices, reconnect backoff, monotonic deadlines, OAuth callbacks/PKCE, token rotation, per-instance storage, renewal retries/cancellation, and account identity. Browser login and runtime Auth Me integration still require the [manual authentication checks](docs/authentication-scenarios.md).

In-game checks: toggle off during a pending visit and confirm no further automation; change destination while disabled and confirm it remains disabled; enable own mode in the Hub and confirm `/is` stops repeating after arrival; switch back to guest mode; enter limbo and confirm `/lobby` → `/play sb` → the configured island; disconnect from Hypixel unexpectedly and confirm delayed reconnection; use Disconnect or cancel a connection and confirm it stays disconnected; test an expired session with Auth Me, including cancelling and successfully completing login.

## Build

Targets Minecraft **26.2** / Fabric and requires Java 25.

```bash
./gradlew build
```

On Windows, use `gradlew.bat build`.

Install the mod jar from `build/libs/` (not the `-sources.jar`). Test reports are written to `build/reports/tests/test/index.html`.

GitHub Actions builds pushes and pull requests. Pushes to `main` also replace the `latest-build` release with `SocialXPFarm-latest.jar`.
