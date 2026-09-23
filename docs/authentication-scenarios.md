# Authentication recovery scenarios

The **Pick a login method** screen is an expected pause: SocialXPFarm opened Auth Me after a rejected session. It does not select Microsoft or complete browser authentication automatically. Leaving this screen open does not trigger a reconnect timeout.

## Multiple Microsoft accounts

Each running instance remembers the Minecraft account UUID whose session was rejected. Recovery requires a different, nonblank online token for **that same UUID**, followed by a return to the original disconnect screen. A changed display name is allowed. A different Minecraft account leaves automatic reconnect paused and logs the expected username once. This checks account identity, not whether the token will be accepted by the server.

In Auth Me **9.3.0+26.2**, hold **Left Ctrl while clicking the Microsoft icon** to request account selection in the browser. Choose the Microsoft account that owns the alt for that instance. Ordinary clicking can reuse the browser's current account. See the pinned [Auth Me method-selection implementation](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/gui/screen/AuthMethodScreen.java).

If you choose the wrong account, use Auth Me's **Re-Login** button on the disconnect screen and repeat with the correct account. SocialXPFarm does not restore the previous client session, store Microsoft credentials, or remember a Microsoft email per instance. The identity check applies to automatic recovery; a manual connection is still your choice.

## Automated regression scenarios

Run:

```bash
./gradlew test --tests '*AuthenticationRecoveryTest'
```

These tests exercise the production `AuthenticationRecovery` gate using synthetic Minecraft users and screen identity markers. They do not contact Microsoft or Hypixel, launch a browser, load Auth Me, or test Fabric screen hooks. The wider `./gradlew build` suite also covers disconnect classification and reconnect deadlines.

| Scenario | Expected result |
|---|---|
| Invalid-session reason | Classified as an authentication failure. |
| Repeated processing of the same failure; chooser stays open | One login handoff; gate stays closed. |
| Auth Me installs a fresh session before its screen closes | Wait until it returns to the original disconnect screen. |
| Browser failure/cancel → chooser → Back | Stay paused with the rejected session; allow a later manual login to complete recovery. |
| Replacement user with the same token, blank token, or `invalidtoken` | Stay paused. |
| Fresh online token for the original alt | Report ready once so the controller can schedule a reconnect. |
| Browser authenticates another alt or the main account | Stay paused; report wrong account once. |
| Same name with another UUID; changed name with original UUID | Reject the former; accept the latter. |
| Two independent recovery gates for different alts | Each accepts only its own account. |
| Login adapter returns without opening a screen | Stay paused; a later manual session renewal can still complete recovery. |
| Recovery state cleared while login is pending | Late authentication completion does not revive that recovery state. |
| Later session expires again | A new authentication cycle can start. |

## Manual two-instance browser test

Status: **not yet executed against live Microsoft/Hypixel services**. Use two launcher instances with different owned Minecraft accounts, labelled **A** and **B**. Record the expected Minecraft username for each. Install only one SocialXPFarm jar per instance, along with Auth Me 9.3.0+26.2 and its dependencies. Enable automation and auto-reconnect; configure a guest destination or choose own-island mode.

Start the recovery cases below when an instance encounters a real invalid/expired-session disconnect. A generic network kick is not an authentication trigger. Do not rely on a fixed session-expiry duration. The automated suite supplies a deterministic trigger without waiting for real expiry.

1. **Reproduce the screenshot:** leave A at **Pick a login method** for at least 30 minutes. Expect no extra browser windows or reconnect attempts. The screen should remain available for input.
2. **Cancel:** choose Microsoft, cancel/back out, and then return from the chooser to the disconnect screen. Expect no reconnect with the unchanged session. Use Re-Login to try again.
3. **Correct account:** hold Left Ctrl while clicking Microsoft; choose A's Microsoft account and finish the browser flow. Expect Auth Me to return to the disconnect screen, then a delayed reconnect and recovery to the configured destination.
4. **Shared browser account:** leave the browser signed into A. When B needs authentication, deliberately complete login as A. Expect B to stay disconnected and its log to say it needs B's username. This deliberately changes B's client session to A; correct it in the next step.
5. **Correct the mismatch:** in B, use Re-Login, then Left Ctrl + Microsoft and select B's account. Expect B to reconnect as B. A should remain connected; there should be no mutual duplicate-login kicks caused by automatic recovery.
6. **Independent pending logins:** when both instances need renewal, complete their browser flows one at a time, checking the instance and account each time. Completing A must not release B's pending recovery.
7. **Offline login:** during another pending recovery, select Auth Me's offline option. Expect recovery to remain paused. Renew the original online account to resume.
8. **Unavailable/failed login:** exercise a browser authentication failure and, separately, an instance without Auth Me installed. Expect no reconnect loop using rejected credentials. Without Auth Me, restart from the launcher to renew the session and reconnect manually.
9. **Manual departure and disabled automation:** return to the server list while recovery is paused; expect no automatic return. Separately, disable automation before the disconnect; expect no Auth Me handoff or reconnect. Re-enable and connect manually to start a fresh recovery context.
10. **Service outage:** an authentication-service-unavailable disconnect should follow ordinary retry/backoff, without opening Auth Me. This is also covered by the reason-classification tests.

Check each instance's `logs/latest.log` for `Disconnect classified as AUTHENTICATE`, `Session rejected`, and—only after the correct login—`Session changed; resuming reconnect recovery` followed by `Reconnecting`. For a mismatch, expect `automatic reconnect remains paused`. Record the jar version, expected/actual Minecraft username, outcome, and elapsed recovery time; do not record access tokens or browser authorization codes.
