# SocialXPFarm

Tiny client-side Fabric 1.21.2 mod for keeping a Hypixel SkyBlock alt on `uaiden`'s island.

## What it does

- Runs only while connected to Hypixel.
- Uses `uaiden` being present in the current server player list as the success signal.
- If `uaiden` is missing long enough, sends `/visit uaiden`.
- Detects the `Visit uaiden` menu and clicks the `Pineapple` profile automatically.
- If the transfer does not succeed, retries the visit flow after a cooldown, covering transient Hypixel server-transfer failures.

The mod is client-only and has no configuration or mixins.

## Build

```bash
./gradlew build
```

The remapped jar is written to `build/libs/`.

GitHub Actions also builds every push/PR and republishes a `latest-build` release from `main`.
