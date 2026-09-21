# SocialXPFarm

Small client-side Fabric **26.2** mod that keeps a Hypixel SkyBlock alt guesting on a configured player's island.

## Install and start

1. Install Fabric Loader and Fabric API for Minecraft **26.2** using Java **25**.
2. Download `SocialXPFarm-latest.jar` from the [latest build](https://github.com/mcneds/SocialXPFarm/releases/tag/latest-build) and put it in your Minecraft instance's `mods/` folder.
3. Optionally install **Auth Me for Fabric 26.2** and its required dependencies for in-game session renewal.
4. Launch once to create `config/socialxpfarm.json`, then close Minecraft and set `targetPlayer` and `profileName`.
5. Relaunch and connect to Hypixel. Recovery starts automatically when both destination values are configured.

## Recovery behavior

The mod treats the `SKYBLOCK GUEST` sidebar as healthy; the island owner does not need to remain online. If guest mode disappears for a few seconds, it rejoins SkyBlock, sends `/visit <targetPlayer>`, and selects a profile in the visit menu.

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

Reconnect delays include up to 20% random variation within the configured cap. Thirty seconds of continuous guesting resets retry history. Returning to the server list or title screen cancels recovery. Manual disconnects stay disconnected, and connecting to another server clears Hypixel recovery.

## Limits

- Auth Me may require browser interaction; authentication and consent screens stay open until you handle them.
- Queue and custom disconnect messages are detected heuristically. New wording may need an update.
- `SKYBLOCK GUEST` alone does not verify the island owner/profile or prove that the server is progressing. The current visit-menu logic can fall back to the only visitable profile head.
- This mod cannot restart a crashed JVM, wake a sleeping computer, or fix an unavailable server or destination restrictions.
- Builds and automated tests pass; the recovery flows still need live Hypixel validation.

See the [disconnect research and recovery matrix](docs/disconnect-recovery.md) for sources, scenario coverage, and the in-game validation checklist.

## Configuration

On first launch the mod creates:

```text
.minecraft/config/socialxpfarm.json
```

It starts idle until both destination values are filled in. Example:

```json
{
  "enabled": true,
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

`targetPlayer` is the username used by `/visit`. `profileName` is the profile name shown in the visit GUI (for example `Pineapple`, `Coconut`, etc.).

All timing settings use ticks as units; 20 ticks is approximately one second. Reconnect delays and connection/limbo watchdogs measure monotonic elapsed time, so low menu FPS and system-clock adjustments do not stretch them. In-world menu/command timers use client ticks.

Existing configs use defaults for omitted settings. Set `autoReconnect` to `false` to disable reconnecting and the Auth Me handoff while retaining in-server recovery. Restart the client after editing the config.

## Stale sessions (optional Auth Me integration)

Install [Auth Me for Fabric 26.2](https://www.curseforge.com/minecraft/mc-mods/auth-me) and its required dependencies alongside this mod. The integration targets [Auth Me 9.3.0+26.2](https://github.com/axieum/authme/tree/v9.3.0%2B26.2); Auth Me is optional and is not bundled.

When a session is rejected, choose Microsoft on the Auth Me screen and complete its browser login. SocialXPFarm waits until Auth Me returns to the disconnect screen with a changed session, then reconnects automatically. Auth Me does not silently refresh credentials; browser interaction may be required. Cancelling authentication leaves reconnects paused, and an offline login does not resume them. Without Auth Me, restart Minecraft to renew the session and reconnect manually.

## Recovery verification

`./gradlew build` runs regression tests for Hypixel address scoping, disconnect classification, queue/throttle notices, reconnect backoff, and monotonic deadlines (including nested translated errors and authentication service outages).

In-game checks: enter limbo and confirm `/lobby` → `/play sb` → the configured island; disconnect from Hypixel unexpectedly and confirm delayed reconnection; use Disconnect or cancel a connection and confirm it stays disconnected; test an expired session with Auth Me, including cancelling and successfully completing login.

## Build

Targets Minecraft **26.2** / Fabric and requires Java 25.

```bash
./gradlew build
```

On Windows, use `gradlew.bat build`.

Install the mod jar from `build/libs/` (not the `-sources.jar`). Test reports are written to `build/reports/tests/test/index.html`.

GitHub Actions builds pushes and pull requests. Pushes to `main` also replace the `latest-build` release with `SocialXPFarm-latest.jar`.
