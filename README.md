# SocialXPFarm

Small client-side Fabric 1.21.2 mod that keeps a Hypixel SkyBlock alt guesting on a configured player's island.

## Behavior

- Runs only on Hypixel.
- Uses the `SKYBLOCK GUEST` scoreboard state as the healthy state, so the island owner does **not** need to be online.
- If guest mode disappears for a few seconds, it starts recovery.
- If the alt is outside SkyBlock, it first runs `/play sb`.
- Runs `/visit <targetPlayer>`.
- Detects the `Visit <targetPlayer>` profile chooser.
- Clicks the configured SkyBlock profile head automatically.
- If the visit menu never appears or the transfer does not complete, it closes the stale menu and retries after a cooldown.

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
  "retryDelayTicks": 100
}
```

`targetPlayer` is the username used by `/visit`. `profileName` is the profile name shown in the visit GUI (for example `Pineapple`, `Coconut`, etc.).

All timing values are in client ticks; 20 ticks is approximately one second.

## Build

Requires Java 21.

```bash
gradle build
```

The remapped mod jar is written to `build/libs/`.

GitHub Actions builds pushes and pull requests. Pushes to `main` also replace the `latest-build` release with `SocialXPFarm-latest.jar`.
