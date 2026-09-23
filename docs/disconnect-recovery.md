# Disconnect research and uptime recovery

Reviewed 2026-09-22 for Minecraft 26.2, Fabric, and Auth Me 9.3.0+26.2; automatic authentication added 2026-09-23.

The objective is time spent at the selected destination: guesting on the intended island, or on your own island in `OWN` mode. F8 or `/sxp off` disables all automation; `/sxp mode own|guest` changes the destination independently. Both settings persist. Own-island recovery uses `/is` and a non-guest sidebar location of `Your Island`. Being connected to Hypixel, sitting in a queue, or repeatedly reconnecting does not establish that objective. Automatic recovery can reduce downtime from transient failures; it cannot guarantee uninterrupted availability.

This is a failure-family inventory, not a claim that every possible server message has been discovered. Hypixel can supply arbitrary disconnect text, change its proxy behavior, or introduce new restrictions. The accompanying [vanilla reason-key inventory](minecraft-26.2-disconnect-keys.txt) was extracted from the exact Minecraft dependency used by this build. Unknown disconnect reasons retain bounded-frequency retries and are logged for diagnosis.

## Evidence and its limits

- **Exact client implementation:** inspected the local Minecraft 26.2 client resources and bytecode for `Connection`, `ConnectScreen`, `ClientPacketListener`, and `DisconnectedScreen`. Connection setup installs a 30-second Netty read timeout. Ordinary network silence therefore already has a timeout. A stream of packets can keep that connection alive without completing a login or world load; the added watchdog covers those distinct stages. The connect-abort hook follows vanilla's synchronization and cancellation sequence.
- **Hypixel operator guidance:** DNS, local network conditions, versions/mods, and upstream routing can all matter. Persistent connection failures warrant network diagnostics, not progressively faster retries. [Connection troubleshooting](https://support.hypixel.net/hc/en-us/articles/360019671939-Solving-Lag-and-Connection-Issues-on-Hypixel), [MTR instructions](https://support.hypixel.net/hc/en-us/articles/360019672439-How-to-Run-an-MTR-for-Hypixel).
- **Server-side incidents:** Hypixel publishes incidents and maintenance separately from the game connection. Consult this when many reconnect attempts fail; the mod does not depend on that website being reachable. [Hypixel Status](https://status.hypixel.net/).
- **Auth Me implementation:** `MicrosoftAuthScreen` obtains a browser authorization code and installs a new Minecraft user. The 26.2 implementation exchanges that code for an access token; it does not persist or expose a background refresh-token workflow. `SessionUtils.setUser` also recreates profile-key services. [Microsoft authentication screen](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/gui/screen/MicrosoftAuthScreen.java), [OAuth utilities](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/util/MicrosoftUtils.java), [session replacement](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/util/SessionUtils.java).
- **Queue and command behavior:** firsthand reports show that queue limbo can reject lobby commands and that excessive command traffic can delay transfers. These are historical observations, not a current protocol contract. Queue and throttle text matching is consequently conservative and explicitly heuristic. [Queue-limbo report](https://hypixel.net/threads/i-got-locked-out-of-skyblock-because-of-the-queue-and-cant-do-any-commands-such-as-lobby.2675105/), [command-throttle reports](https://hypixel.net/threads/you-are-sending-commands-too-fast-please-slow-down.5879639/).
- The official SkyBlock wiki is no longer a usable current reference: its private-island URL redirects to the operator's closure announcement. Island/menu assumptions below need live validation. [Official wiki closure](https://hypixel.net/threads/end-of-the-official-hypixel-wiki-july-2026.6112020/).

## Scenarios and solutions

“Implemented” describes the code's response, not a completed live Hypixel test. Conditions within one family can produce different wording.

| Failure family | Recovery that preserves uptime | Implementation / remaining action |
|---|---|---|
| AFK transfer into ordinary limbo | Let the transition settle; `/lobby`, then `/play sb`, then visit. | Implemented using missing/limbo sidebar after the grace period. |
| Limbo rejects or ignores recovery commands | Stop cycling commands forever; disconnect cleanly and establish a new connection. | Implemented after `limboReconnectTicks`, default 120 seconds; queued/throttled time does not count. |
| Actual admission queue while in limbo | Keep the existing queue position and wait. | Implemented for recognized system chat, action-bar, title/subtitle notices, or a queue sidebar. Signals expire five minutes after the last recognized notice. |
| Queue completed but left in limbo | Resume lobby recovery when queue evidence clears or expires. | Implemented lease expiry; unrecognized queue wording remains a limitation. |
| Kicked from island into hub/lobby | Re-enter SkyBlock if necessary and revisit. | Existing recovery, now with exponential retry delay. |
| Island instance reboot or transfer failure | Allow transfer time, close stale visit GUI, retry destination with backoff. | Implemented. No attempt to keep a shutting-down instance alive. |
| Island full, temporarily inaccessible, or visit command disabled | Remain connected; retry at a lower frequency until destination is available. | Menu/join timeouts back off to `maxRetryDelayTicks`. Server-wide disabling of visits has occurred historically. [Firsthand report](https://hypixel.net/threads/anyone-know-why-visiting-peoples-islands-is-disabled.5084546/). |
| Island privacy restriction, explicit island ban, missing/deleted profile, wrong username | Owner/configuration must be corrected; network reconnect cannot grant access. | Current visit timeouts back off. Precise per-message destination restriction handling is not implemented. |
| Visit GUI missing, delayed contents, rejected click, wrong menu | Wait for contents, inspect only GUI slots, timeout and retry. | Implemented. Menu layout and configured-profile matching still need live verification. |
| Temporary loss of sidebar during world loading | Avoid treating the loading screen as limbo; resume after loading. | Implemented screen guard plus existing guest-exit grace. |
| TCP timeout, connection reset, EOF, broken pipe, remote close | Reconnect after bounded exponential delay. | Implemented; unknown generic disconnects use the same policy. |
| DNS lookup failure, refused connection, unreachable route | Repeat normal hostname resolution on later attempts; allow connectivity to return. | Implemented reconnect path. Persistent faults need DNS/router/ISP diagnosis; the mod does not change OS networking. |
| Hypixel proxy/backend restart, unavailable authentication service, upstream outage | Keep retrying at capped frequency; do not reopen OAuth for an authentication-service outage. | Implemented; server status is an external diagnostic aid. |
| Server full, login flood protection, packet-rate limit, stale “already connected” state | Wait at least 60 seconds before reconnecting. | Implemented cooldown category. The server may enforce a longer unpublished cooldown. |
| Commands sent too quickly while connected | Pause commands before trying again. | Implemented 20-second pause for recognized notices; other mods' command rates are outside our control. |
| DNS/TCP/login connection screen never completes | Abort the pending connection before creating a replacement. | Implemented 120-second per-screen watchdog. Late connection results see vanilla's abort flag. |
| Configuration or terrain-loading screen hangs while traffic continues | Cleanly disconnect after a generous timeout and retry. | Implemented watchdog for `ServerReconfigScreen` and `LevelLoadingScreen`. A different interactive screen suspends that stage timer. |
| Completely silent established connection | Let vanilla's network timeout disconnect it, then reconnect. | Existing Minecraft behavior plus our reconnect flow; no duplicate packet-silence watcher. |
| World appears frozen but valid packets and a guest sidebar continue | Need evidence of actual server progress, not movement or scoreboard title alone. | Not reliably detectable yet. No periodic forced reconnect that would interrupt healthy AFK sessions. |
| Expired/invalid login session or unverified username | Renew the paired account, then reconnect using the new session. | Per-instance refresh-token renewal implemented; Auth Me installs the session. Initial pairing or revoked credentials need browser interaction. |
| Expired/missing/invalid profile signing key | Recreate the session/key services; verify system time if expiry recurs. | Auth Me handoff includes these keys, including the new signature-error variant. OS clock correction or a client restart may still be needed. |
| Microsoft password change, revoked session, MFA/account selection, OAuth failure | Complete the account's legitimate authentication flow. | When silent renewal needs interaction, desktop pairing opens once, or configured remote mode sends a private Discord alert and waits for a phone sign-in request. Network/service failures retry every 60 seconds. Rotated refresh tokens are saved before downstream Xbox/Minecraft requests. |
| Another device actually logs into the same account | Stop the competing client or choose which instance owns the session. | Explicit duplicate-login eviction pauses automatic recovery, avoiding an endless mutual-kick cycle. Distinct stale “already connected” messages use cooldown. |
| Multiplayer privacy disabled, global account ban, server ban, security block, whitelist restriction | Resolve the account/server restriction before reconnecting. | Recognized reasons pause. Security blocks require the operator's appeal/recovery process. [Hypixel security-block guidance](https://support.hypixel.net/hc/en-us/articles/23957902741394-How-to-Handle-Suspicious-Activity-Account-Blocks-and-Keep-Your-Account-Secure). |
| Protocol/version incompatibility | Use a mutually supported Minecraft/Fabric/mod combination. | Explicit incompatibility pauses immediately. The support version list can lag release changes; verify the actual server rejection before changing this 26.2 project. [Version guidance](https://support.hypixel.net/hc/en-us/articles/360019634940-How-to-Change-Your-Game-Version-for-Hypixel). |
| Decoder/encoder exception, malformed/compressed packets, registry/configuration errors | Retry a few times in case only one backend is faulty; investigate persistent errors. | Three automatic protocol retries, then pause. Resolve incompatible mods, client data, or server defects. |
| Chat signature/order errors or invalid movement/data packets | Reconnect to clear transient connection state; investigate if repeated. | Known chat/data protocol errors use the bounded retry policy. Generic flying/idling/operator kicks remain transient retries. |
| Required pack rejected or code-of-conduct/custom screen rejected | User must accept the intended requirement or leave the server. | Recognized disconnect reasons pause; consent prompts are never auto-clicked. |
| Pack download/reload failure or resource-pack crash | Inspect the pack setting/cache and mod compatibility. | Not automatically repaired. Hypixel documents pack settings, cache repair and restart steps. No cache deletion is performed by this mod. [Resource-pack troubleshooting](https://support.hypixel.net/hc/en-us/articles/360019635040-How-to-Fix-Resource-Pack-Issues-on-Hypixel). |
| User presses Disconnect, cancels connecting, returns to menu, or joins another server | Preserve that decision. | Implemented; returning to title/server list clears recovery. |
| JVM crash, out-of-memory exit, process hang, OS sleep/reboot, power/network loss | Restore the host/process, then authenticate and reconnect. | A mod cannot execute after its JVM stops. Process supervision/autostart and host power policy are separate work; not installed or changed here. |
| Brand-new custom reason or localized Hypixel text | Preserve reason, retry at capped frequency, then extend detection from observed evidence. | Translation keys are language-independent; custom English matching is heuristic. No completeness guarantee. |

## Implemented recovery policy

1. Remember only an attempted Hypixel destination. Normal client-selected connections to other hosts clear that scope. Reconnect with the saved `ServerData`, retaining server resource-pack preferences.
2. Wait through loading and detected queues. Pause on command-throttle notices. Queue notices are consumed from server/game messages rather than signed player chat; anchored patterns avoid ordinary `Player: ...` messages. Other plugins can still format text differently.
3. Recover in-server first: lobby → SkyBlock → configured visit menu. Failed joins/visits back off from five seconds to sixty seconds by default. Missing-sidebar limbo that persists for two minutes escalates to a full reconnect.
4. Classify disconnect components recursively, including nested translation arguments and siblings. Precedence is manual restriction → authentication → bounded protocol repair → cooldown → ordinary retry.
5. Ordinary reconnect delays are 10, 20, 40, then 60 seconds before jitter. Add less than 20% jitter without exceeding the cap. Capacity/throttle/stale-connection cases impose a 60-second floor even if a user configures a lower normal cap.
6. Connection and limbo watchdogs, reconnect delays, and signal leases use `System.nanoTime`. They advance even when menu rendering/client tick delivery is slow, and do not use the adjustable wall clock. Checks still require the client thread to run.
7. Backoff and protocol-failure history reset only after 600 consecutive ticks at the selected destination, approximately thirty seconds. A brief successful login or guest-state flicker does not reset them.
8. Log the disconnect reason and selected policy once for each failure screen. Preserve vanilla's protocol report facilities. Repeated protocol failures pause after three automatic retries; transient outage retries remain unlimited in count but capped in frequency. [Hypixel disconnect-report instructions](https://support.hypixel.net/hc/en-us/articles/20335547218322-Reporting-Server-Disconnect-Logs-to-Hypixel).

## What still limits unattended uptime

- **Authentication:** SocialXPFarm now owns a per-instance refresh-token flow after one-time pairing. Revoked/expired refresh credentials and Microsoft security prompts still need human sign-in. Auth Me remains necessary to install the renewed Minecraft session. Live OAuth and runtime session installation remain unverified; see the [authentication scenarios](authentication-scenarios.md).
- **Healthy-state accuracy:** the existing `SKYBLOCK GUEST` title does not prove the island owner/profile or that the server is progressing. The existing sole-head fallback can select a different available profile if the configured one is missing. Exact target verification is a separate correctness improvement; this research does not claim it is solved.
- **Queue recognition:** the five-minute signal lease avoids pausing forever on stale text, but could expire in a real queue that stops publishing recognizable updates. Conversely, stale queue text can delay recovery. Verify actual current server packets before shortening the lease.
- **Client-initiated transfers:** ordinary Hypixel backend transfers are handled through changed world/sidebar state. If Minecraft is explicitly transferred to a non-Hypixel hostname/IP, the current host allowlist clears recovery. Following arbitrary transfer destinations automatically is not implemented.
- **Interactive prompts:** an unexpected pack, consent, or auth screen can require human input. Watchdogs deliberately cover known noninteractive loading screens, not every screen.
- **External failures:** networking, server admission, JVM crashes and host suspension remain outside the mod's execution domain. A supervisor must distinguish a crash from an intentional quit, keep restart backoff, and avoid concurrent clients on one account. No launcher/supervisor changes were made.

## Validation and next operational steps

Automated tests cover reason classification and precedence, nested translated messages, ordinary outages versus stale sessions, Hypixel host scoping, retry-delay overflow/capping, queue/throttle examples and false positives, monotonic expiry, signal renewal, and `nanoTime` wraparound. The build also compiles and packages the mixins. This does **not** validate runtime mixin application or server-side message formats.

Before unattended use, exercise these cases in a controlled client session:

| Check | Expected result |
|---|---|
| Ordinary AFK limbo, then a limbo that ignores commands | First recover in-server; second reconnect after the configured watchdog. |
| Queue position in chat, action bar, title and subtitle | No lobby/visit commands or forced reconnect while recognized queue evidence remains fresh. |
| Queue expires or reaches SkyBlock | Recovery resumes; entering SkyBlock clears the queue lease. |
| Command throttle | No additional recovery commands for twenty seconds. |
| Lost network and later restoration | Backoff continues; reconnection resumes without manual intervention. |
| Login/configuration/terrain screen stalled | One connection is aborted before another starts; no late ghost connection. |
| Transient success followed by another kick | Backoff stays elevated until thirty seconds of continuous guesting. |
| Fourth repeated protocol failure | Recovery pauses with the original reason and report controls available. |
| Invalid session, Auth Me absent/present, cancel, offline login, wrong alt, successful Microsoft login | Correct pause/handoff; only a changed online session for the original Minecraft UUID resumes retry. See the [detailed authentication scenarios](authentication-scenarios.md). |
| Explicit account restriction, version mismatch, duplicate-login eviction | Pause and preserve the reason. |
| Manual disconnect/cancel and another server | No automatic return to Hypixel. |
| Owner offline, island full/private, renamed/missing profile, changed menu | Confirm destination correctness and lack of rapid retries. |

For real uptime measurement, collect **confirmed-target guest seconds / intended-running seconds**, recovery duration by reason, retry counts, and time awaiting authentication/queue/intervention. Current logs support diagnosis but do not implement those counters or a target-identity probe. Use observed failures to refine message detection and timeout values rather than assuming lower delays always improve uptime.
