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
| Invalid or expired session | Open optional Auth Me and wait for a renewed online session. |
| Recurring protocol error | Allow three automatic retries, then pause for investigation. |
| Ban, account restriction, incompatible version, or another device logs in | Pause until the underlying issue is resolved. |

Reconnect delays include up to 20% random variation within the configured cap. Thirty seconds continuously at the selected destination resets retry history. Returning to the server list or title screen cancels recovery. Manual disconnects stay disconnected, and connecting to another server clears Hypixel recovery.

## Limits

- Auth Me may require browser interaction; authentication and consent screens stay open until you handle them.
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

## Stale sessions (optional Auth Me integration)

Install [Auth Me for Fabric 26.2](https://www.curseforge.com/minecraft/mc-mods/auth-me) and its required dependencies alongside this mod. The integration targets [Auth Me 9.3.0+26.2](https://github.com/axieum/authme/tree/v9.3.0%2B26.2); Auth Me is optional and is not bundled.

When a session is rejected, choose Microsoft on the Auth Me screen and complete its browser login. SocialXPFarm waits until Auth Me returns to the disconnect screen with a changed online session for the **same Minecraft account**, then reconnects automatically. Auth Me does not silently refresh credentials; the **Pick a login method** screen waits for your input. Cancelling authentication leaves reconnects paused, and an offline login does not resume them. Without Auth Me, restart Minecraft to renew the session and reconnect manually.

For alts with different Microsoft accounts, hold **Left Ctrl while clicking Microsoft's icon** in Auth Me 9.3.0+26.2 to request browser account selection. Choose the account belonging to that instance. If the browser signs into your main account or another alt, automatic reconnect stays paused; use **Re-Login** and choose the correct account. The mod checks Minecraft UUIDs and does not store Microsoft login credentials. See the [authentication scenarios](docs/authentication-scenarios.md) for automated coverage and a two-instance browser test.

## Recovery verification

`./gradlew build` runs regression tests for Hypixel address scoping, disconnect classification, queue/throttle notices, reconnect backoff, monotonic deadlines, and the authentication session gate (including cancellation, wrong-alt login, and delayed completion). Browser login and runtime Auth Me integration still require the [manual authentication checks](docs/authentication-scenarios.md).

In-game checks: toggle off during a pending visit and confirm no further automation; change destination while disabled and confirm it remains disabled; enable own mode in the Hub and confirm `/is` stops repeating after arrival; switch back to guest mode; enter limbo and confirm `/lobby` → `/play sb` → the configured island; disconnect from Hypixel unexpectedly and confirm delayed reconnection; use Disconnect or cancel a connection and confirm it stays disconnected; test an expired session with Auth Me, including cancelling and successfully completing login.

## Build

Targets Minecraft **26.2** / Fabric and requires Java 25.

```bash
./gradlew build
```

On Windows, use `gradlew.bat build`.

Install the mod jar from `build/libs/` (not the `-sources.jar`). Test reports are written to `build/reports/tests/test/index.html`.

GitHub Actions builds pushes and pull requests. Pushes to `main` also replace the `latest-build` release with `SocialXPFarm-latest.jar`.
