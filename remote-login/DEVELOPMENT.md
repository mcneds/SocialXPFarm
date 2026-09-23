# Developer validation

The default test suites and scenario runner use synthetic credentials and fake Discord delivery. They do not authenticate real accounts, send Discord messages, or connect to Hypixel.

From the repository root:

```bash
./gradlew build
PYTHONPATH=remote-login remote-login/.venv/bin/python -m unittest discover -s remote-login/tests -v
remote-login/.venv/bin/python remote-login/dev_scenarios.py
```

From an extracted companion release:

```bash
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python dev_scenarios.py
```

CI runs the companion tests/scenario runner on Python 3.12 and 3.14. Release publication waits for those checks and the Java build. Dependencies are pinned in `requirements.lock`; `requirements.in` lists direct dependencies. Update the pins together and run both Python versions when changing libraries.

Linux CI requires `systemd-analyze`. Service tests validate generated units with `systemd-analyze verify`, including installation paths containing spaces and literal `%` characters. They use temporary files and an existing executable, without starting a service or contacting Discord. That validation test skips locally if systemd is unavailable; the output-format tests still run.

## Coverage layers

| Layer | Scenarios |
|---|---|
| Microsoft transport | Pending → successful phone sign-in, permanently increased polling after slowdown, temporary outage delays, local/provider expiry, declined consent, bad code, wrong UUID, interruption, unsafe verification URL, downstream errors after a consumed grant. |
| Browser callback | Account selection, PKCE S256 and configured client binding; exact redirect/state, malformed/duplicate/foreign callback rejection, expiry without waiting, cancellation and late publication/write rejection, one-time redemption across pasted and HTTP callbacks, wrong UUID and downstream failure without saving. |
| Credential storage | Existing 1.2 files migrate to Auth Me's client ID, custom issuer binding persists and is used on refresh, rotation, file permissions, symlink rejection, redaction. |
| Java command policy | Correct active request, stale process/context, expiry, unknown actions, disabled automation, duplicate start during sign-in, cancellation allowed only during phone sign-in, secure configuration, explicit capability for remote tests. |
| Remote phone test | Idle → test request → browser account picker → callback → paired, saved credential preservation until verified, duplicate/stale actions, cancellation, account changes, disabled automation, wrong accounts and expired codes, no desktop browser or live session replacement. |
| Shared protocol | `tests/fixtures/snapshot.json` and `browser-snapshot.json` are parsed/round-tripped by Java and validated by Python. They contain synthetic device/browser prompts only. |
| Companion state machine | Owner allowlist, per-instance bearer isolation, acknowledgements/retries, duplicate clicks, concurrent alts, restarts, active duplicate processes, stale heartbeats, expired commands, process/context/account changes. |
| Local HTTP | Real loopback HTTP exchange, authorization failures, invalid JSON/schema, body limits, browser Origin rejection, Host validation, unsupported routes/methods. |
| Discord adapter | Fake interactions, owner/DM-only enforcement, early deferral, selected-instance routing, stale buttons, persistent component IDs and SDK constraints, safe rendering, owner-only callback modals, ephemeral deferral, revalidation on submission, no code echo or exception payload logging. |
| Account email hints | Optional configuration migration, owner/DM-only set/view/clear, independent hints per instance, protected persistence and write failure rollback, updated request text without duplicate alerts, browser guidance with unchanged Microsoft verification URLs. |
| Notification reconciliation | One alert per request, updates instead of repeated alerts, blocked DMs/outages, expiry/offline code removal, restart deduplication, deleted messages, terminal states and independent alts. |
| Local tooling | Wizard creates independent secrets, reruns preserve identity, owner-only atomic writes, symlink rejection, systemd unit generation, credential-free release packaging. |

The development scenario runner executes the two-alt workflow through the actual companion registry and notification code with an in-memory Discord adapter. Assertions make failures exit nonzero. It prints only scenario outcomes, never credentials. Java's injected HTTP transport and clocks let polling/expiry run instantly, and existing cancellation tests deliberately deliver late work after cancellation.

## Protocol and extension points

The mod polls `POST /v1/instances/{id}/exchange` every two seconds with its instance-specific bearer secret. Bodies carry `runId`, the last acknowledged command ID, and a snapshot on change or every 15 seconds. The companion responds with a command or requests snapshot resynchronization. Snapshot fields are allowlisted; private Microsoft tokens/device codes are rejected if introduced as fields. Microsoft requests run in each Minecraft instance, not in Python.

Commands are limited to `login`, `cancel`, `test`, and `callback`, and contain their own ID, process ID, authentication context and expiry. Version 1.3.1 adds the optional `canTest` snapshot capability (absent means false for older mods) and `paired` state. Update the companion before starting the newer mod because older companions reject unknown snapshot fields. Both sides validate lifecycle state; a command acknowledged after cancellation cannot revive the cancelled operation. Version 1.3.2 adds optional `browserPrompt` (`authorizationUri`, `expiresAt`) and a transient `callback` command payload containing the pasted URL. The companion validates its exact redirect/state against the prompt; Minecraft independently checks its own authoritative receiver, request lifecycle, monotonic five-minute deadline, and single-use completion. Neither side fetches the submitted URL. Only Microsoft’s fixed token endpoint receives the code, with the instance-local PKCE verifier. Callback command payloads are never persisted or echoed. Cancellation may supersede an undelivered callback. Old device prompts remain supported for upgrades. Test new protocol fields on both sides using the shared fixtures. The companion does not expose a shell, arbitrary game commands, account-switching API, or public HTTP UI.

## Live acceptance checklist

Use two owned accounts, distinct game directories, a dedicated test bot and your allowlisted Discord user. Tests below require explicit real account interaction and are not performed by the unit suite.

1. Install the same release of mod and companion; verify bot DMs and status from your phone on mobile data, with no router forwarding or VPN.
   Run `/sxp test` for each idle, connected instance entirely through Discord, tap Sign in, and finish on your phone by copying the final localhost address into **Paste callback**. Verify the account picker allows another email even with a different Microsoft account cached. Verify `paired`, a saved renewal credential, and the original game connection remaining active. Repeat with a wrong account, expired code, cancellation, and disabling automation. This test does not establish live session installation or reconnect behavior; use the recovery scenarios below for those.
2. Cause a legitimate stale-session recovery requiring interaction. Verify one alert names the correct instance/account. Waiting to click must not generate expiring codes automatically.
3. Tap Sign in, complete Microsoft consent on the phone, and paste the localhost callback promptly into the owner-only form. Observe session installation, reconnect, and destination restoration in order. Also try the link on the Minecraft PC and confirm direct localhost completion needs no paste.
4. Choose the other alt during a test attempt. Verify no wrong-account credential/session is installed; retry with the intended account.
5. Exercise both instances concurrently; verify independent codes and recovery, including a browser already logged into the other Microsoft account. Paste Alt A’s callback into Alt B’s form and verify rejection without consuming Alt B’s request.
6. Cancel and expire codes; repeat button presses; try an old message after restarting an instance. Verify no extra grants or stale session installation.
7. Disable automation during pending sign-in. Verify cancellation and no late credential writes/reconnect; re-enable and reconnect manually to start a fresh context.
8. Open a form, then cancel/expire/restart the instance before submitting; verify it cannot revive the old login. Restart the companion during a pending request and reopen the persistent callback form after heartbeats resume; block/unblock DMs; interrupt network connectivity. Verify reconciliation and eventual delivery without login loops.
9. Check existing desktop login and saved 1.2 credentials remain usable. Verify ordinary successful silent renewal causes no notification.
10. Inspect logs/artifacts for accidental credential disclosure. Never attach actual `config.json`, `remote.json`, `account.json`, codes, or tokens to bug reports.

Record jar/companion versions, expected/actual account names, the scenario and observed result. Automated tests do not establish runtime Fabric mixin correctness or actual server message/destination semantics.
