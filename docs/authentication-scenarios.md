# Automatic authentication scenarios

Version 1.2.0 added saved per-instance Microsoft renewal. Auth Me 9.3.0+26.2 supplies the session installation API. SocialXPFarm performs its own initial OAuth authorization-code flow with PKCE, stores the refresh token, and subsequently exchanges it for Microsoft → Xbox → XSTS → Minecraft credentials. No launcher account files are read.

Version 1.3.0 adds optional phone sign-in through private Discord DMs. See the [remote setup guide](../remote-login/README.md) and [extensive developer scenarios](../remote-login/DEVELOPMENT.md). The original desktop scenarios below still apply when remote mode is not configured.

## Setup and expected behavior

Launch each instance with its intended Minecraft account, join a server, and run `/sxp auth login` once. Select that alt's Microsoft account in the browser and complete consent. `/sxp auth` confirms pairing. Each instance stores only its own account under `config/socialxpfarm-auth/account.json`, protected with owner-only permissions. The file contains an unencrypted refresh credential: exclude it when sharing an instance. `/sxp auth forget` removes it locally.

Subsequent invalid-session disconnects renew silently. The renewed Minecraft UUID must match the rejected session's UUID. The saved refresh token is rotated before downstream calls, so a later Xbox/Minecraft outage does not discard Microsoft's replacement. Renewal runs off the client thread; temporary network failures, HTTP 408/429, and server errors wait 60 seconds before retrying. Only one request chain runs at a time for that recovery.

Microsoft can expire/revoke refresh credentials or require interaction. These cases open the pairing screen once; cancellation does not reopen a browser every tick. Initial pairing forces account selection, uses a random loopback port per login, and validates OAuth state and PKCE. An ordinary Auth Me Re-Login remains available but does not save a refresh token for this feature.

Disabling recovery, leaving the failure screen for the server list/title, or choosing another server cancels automatic renewal. Late completion cannot install a session or write credentials after cancellation. Session installation occurs on the Minecraft client thread, only while the original disconnect screen is active and its rejected user has not been manually replaced. Saved pairing survives an ordinary restart and the automation toggle.

## Automated tests

Run `./gradlew build`, or target the new flow with:

```bash
./gradlew test --tests '*auth.*' --tests '*AuthenticationRecoveryTest'
```

| Area | Covered cases |
|---|---|
| OAuth callback | Wrong/duplicate state, rejected consent, independent simultaneous ports/state/verifiers, account-selection and offline-access scopes, standard PKCE test vector. |
| Token exchange | Full silent exchange chain, one-time code exchange with PKCE and exact redirect URI, rotation before downstream outage, retained token if replacement omitted, wrong Minecraft UUID, revoked consent, transient HTTP errors, malformed responses, network failures. |
| Storage | Persistence across new store instances, rotation without leftover temporary files, owner-only POSIX permissions, separate alt directories/UUIDs, deletion, malformed content, symbolic-link rejection, redacted diagnostics. |
| Renewal worker | One in-flight operation, no browser for a saved account, missing/mismatched account needs pairing, 60-second retry using the rotated token, revoked credentials do not loop, cancelled late work cannot save/deliver, wrong account cannot be delivered. |
| Existing session gate | Original screen identity, manual auth cancellation, unchanged/blank/offline tokens, wrong alt, successful login, reset, and another authentication cycle. |

The callback tests use actual local HTTP listeners and synthetic responses. Token exchanges use an injected HTTP transport. **No test signs into Microsoft, contacts Hypixel, or validates runtime Auth Me mixins/session installation.**

## Live two-alt acceptance test

Status: **not yet run**. Use separate launcher directories for owned accounts A and B, only one SocialXPFarm jar per instance, and Auth Me 9.3.0+26.2 with its dependencies. Enable automation and `autoReconnect`, and configure guest recovery or select own-island mode.

1. **Pair A:** launch as A, run `/sxp auth login`, and finish sign-in as A. Expect a success message and `/sxp auth` to report pairing.
2. **Wrong account during B setup:** launch as B and run the same command. Intentionally select A in the browser. Expect the setup screen to reject the account, B's client session to remain B, and no credential for A to be saved in B's instance. Click Sign in again and choose B; expect pairing to complete.
3. **Restart:** restart both instances with their respective launcher accounts. `/sxp auth` should still report each as paired without another browser login.
4. **Silent renewal:** when A encounters a genuine expired/invalid-session disconnect, expect a background renewal with no method-selection screen or browser window, then a reconnect as A and return to its configured destination. Repeat with B while the browser remains logged into A. A generic network kick does not trigger renewal; do not rely on a fixed session-expiry duration.
5. **Concurrent recovery:** when both accounts need renewal, verify each reconnects as its own account without mutual duplicate-login kicks. Account names and UUIDs in logs should match their instances.
6. **Network outage during renewal:** interrupt connectivity, then restore it. Expect retry waits of 60 seconds and eventual recovery without browser login. The automated test specifically covers a failure after refresh-token rotation.
7. **Revoked consent:** revoke the test account's grant through Microsoft's account controls, then trigger renewal. Expect pairing to request interaction once. Cancel and leave it paused; verify that browser windows do not repeatedly open. Complete pairing again to restore automatic renewal.
8. **Cancel/disable:** leave a pending recovery for the server list, or disable automation before a failure. Expect no automatic session installation/reconnect. In a running instance, `/sxp auth forget` removes the saved account; `/sxp auth` should then require pairing.
9. **Missing/incompatible Auth Me:** expect a clear setup message or logged installation failure, with recovery paused. Install the supported version and restart.

Inspect `logs/latest.log` for `Disconnect classified as AUTHENTICATE`, `Automatic login`, `Session changed; resuming reconnect recovery`, and `Reconnecting`. Record build version, expected/actual Minecraft username, result, and recovery time. Never include `account.json`, access/refresh tokens, or callback authorization codes in reports.

## References and limits

- [Microsoft refresh tokens](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens): tokens can be rotated, expired, or revoked; fresh interaction may be required.
- [Microsoft authorization-code flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow): PKCE, OAuth state, offline access and refresh requests.
- [Auth Me's pinned OAuth implementation](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/util/MicrosoftUtils.java): public client registration and Xbox/Minecraft exchange endpoints used by this integration.
- [Auth Me's session installation](https://github.com/axieum/authme/blob/v9.3.0%2B26.2/common/src/main/java/me/axieum/mcmod/authme/api/util/SessionUtils.java): recreates the Minecraft session services, including profile keys.

The OAuth registration is owned by Auth Me; changes to that registration or Microsoft's Xbox/Minecraft access rules may require an update. Token files are filesystem-protected, not encrypted by an OS keychain. Independent instances need separate game directories. Pairing does not replace launcher authentication, automatically choose a launcher account on startup, or restart a crashed client.
