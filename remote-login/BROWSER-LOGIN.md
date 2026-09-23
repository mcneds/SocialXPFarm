# Browser-only phone login (1.4.0, awaiting live registration verification)

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

The optional HTTPS flow is **Discord → Sign in → Microsoft account picker → confirmation page**. It removes the localhost error and manual callback form. It requires your own Microsoft application registration and a callback domain, such as `https://auth.mcneds.dev/oauth/callback`.

This feature is disabled by default. Keep the working 1.3.2 setup until the application and callback are verified on one test instance. Passing unit tests does not prove Microsoft/Minecraft accepts a new app registration. Do not migrate other instances if the first full sign-in fails.

## 1. Register and verify the Microsoft application

Sign into [Microsoft Entra](https://entra.microsoft.com), open **App registrations → New registration**, and create an application named `SocialXPFarm remote login` that supports **personal Microsoft accounts**. A tenant where you can register applications is required; an ordinary personal Microsoft login alone may not provide that access.

In **Authentication**, configure a **Mobile and desktop applications / public client** redirect with exactly:

```text
https://auth.mcneds.dev/oauth/callback
```

Enable public client flows if required by the registration settings. Do not register this as an SPA or create a client secret: code redemption and refresh run in the Minecraft instance, using PKCE. If the portal does not permit this native HTTPS redirect, stop and resolve the registration configuration instead of substituting a different platform or copying Auth Me's client ID.

Copy the **Application (client) ID**. This ID is public; passwords, bot tokens, tunnel credentials, and authorization codes are not. The setup below uses `YOUR_APPLICATION_ID` as a placeholder.

References: [Microsoft redirect rules](https://learn.microsoft.com/en-us/entra/identity-platform/reply-url) and [authorization-code/PKCE flow](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow). A successful Microsoft sign-in alone is insufficient: the test must also complete Xbox and Minecraft authentication and verify the expected Minecraft UUID. A Minecraft “Invalid app registration” response is treated as a configuration failure, never as a successful pairing.

## 2. Prepare the companion, without switching any instances

Use the 1.4.0 candidate companion with its virtual environment. From the companion directory (containing `sxp_remote/` and `.venv/`):

```bash
.venv/bin/python -m sxp_remote web-config --client-id YOUR_APPLICATION_ID
systemctl --user restart socialxpfarm-remote.service
```

The default redirect is `https://auth.mcneds.dev/oauth/callback`. Override it with `--redirect-uri` only when that exact address is registered. The new callback listener binds only `127.0.0.1:38472`; the private instance API remains on `127.0.0.1:38471`. `web-config` does not change any instance's OAuth client or saved account.

## 3. Connect the callback domain using Cloudflare Tunnel

Install `cloudflared` using [Cloudflare's official installation instructions](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/). Authenticate locally, create a dedicated tunnel, and route only the unused callback subdomain:

```bash
cloudflared tunnel login
cloudflared tunnel create socialxpfarm-auth
cloudflared tunnel route dns socialxpfarm-auth auth.mcneds.dev
```

Select the `mcneds.dev` zone during the browser login. If `auth.mcneds.dev` already has a DNS record, inspect it before making changes; do not overwrite an unrelated service. Leave `kuemmerle.dev` and other DNS records unchanged.

Copy the tunnel UUID printed by `tunnel create`, then run:

```bash
.venv/bin/python -m sxp_remote tunnel-config --tunnel-id YOUR_TUNNEL_UUID
cloudflared --config ~/.config/socialxpfarm-remote/tunnel.yml tunnel ingress validate
.venv/bin/python -m sxp_remote tunnel-service-unit --output ~/.config/systemd/user/socialxpfarm-auth-tunnel.service
systemctl --user daemon-reload
systemctl --user enable --now socialxpfarm-auth-tunnel.service
systemctl --user status socialxpfarm-auth-tunnel.service --no-pager
```

The generated configuration uses a locally managed tunnel, the credential file in `~/.cloudflared/`, and a narrow `/oauth/` route allowlist. Its catch-all returns 404. It never exposes the private instance API. The service restarts on failure and needs no router forwarding. Regenerate the unit if you move `cloudflared` or the configuration. Generated files are replaced atomically; failed generation does not truncate an existing unit.

Use no Cloudflare Access login/challenge or cache rule on these callback routes. Microsoft must be able to return the browser's form POST directly. Keep request/body debug logging disabled. The application sends `Cache-Control: no-store`; the authorization code arrives in the POST body, never the public URL. A direct GET of `/oauth/callback` intentionally returns 405; that is not a failed tunnel. Unknown routes, including `/v1/instances/...`, must return 404.

## 4. Verify one instance before migration

Install the candidate mod on one test instance. Enable HTTPS sign-in for only that instance:

```bash
.venv/bin/python -m sxp_remote web-enable --instance main
```

Use the intended label or ID if different from `main`. This saves its prior OAuth settings in protected `config/socialxpfarm-auth/remote-before-web.json`, preserves instance identity/secrets and `account.json`, and changes only its client ID and redirect. Restart that Minecraft instance.

In the bot's DM, run `/sxp test` for that instance and tap **Sign in → Choose Microsoft account**. Test both the PC browser and a phone on mobile data with another Microsoft account already cached. The expected result is:

1. Microsoft lets you choose the correct account without incognito.
2. The browser reaches the HTTPS confirmation page, without localhost or copying.
3. The page first says it is checking the Minecraft account.
4. Only after UUID verification does it say **Sign-in complete**, and Discord reports **paired**.
5. The existing Minecraft connection remains intact and the instance has saved its verified renewal credential.

Stop if Microsoft rejects the registration, requires a secret, or Minecraft denies application access. Resolve that external requirement before migrating other instances. The mod reports a recognized Minecraft registration rejection safely, without echoing provider response bodies. Do not work around registration restrictions by substituting another application's ID.

After this first success, run `web-enable` separately for each remaining instance, update/restart each mod, and repeat the phone test. Also validate a legitimate stale-session recovery: account verification → session installation → reconnect → destination restored. The connected `/sxp test` alone does not test reconnect behavior.

## Rollback and operation

To restore an instance's previous browser login:

```bash
.venv/bin/python -m sxp_remote web-disable --instance main
```

Restart that instance. Saved credentials stay intact and continue refreshing with the client ID that issued them. To remove the web endpoint after rolling back all instances, stop/disable `socialxpfarm-auth-tunnel.service`, remove `browserCallback` from the protected companion configuration, and restart the companion. Do not delete `account.json` as part of rollback.

Only the configured Discord owner can start or cancel login attempts. The public callback can complete a matching active attempt but cannot start login or issue game commands. PKCE verifiers and reusable tokens remain in Minecraft. Cloudflare terminates HTTPS and carries the short-lived code to the companion, which holds it only in memory until delivery/expiry. The callback and confirmation pages use no third-party scripts or analytics.

Browser confirmation uses an opaque five-minute receipt with a Secure, HttpOnly cookie scoped to that result page. Different tabs/instances have independent receipts. A received callback is not proof of successful login. Wrong accounts, cancelled attempts, stale processes, expiry and offline instances never display success. Replays do not create new receipt cookies or redeem another code.

A companion restart loses in-memory callback deliveries/receipts. It reconciles state from the mods again; check Discord for an already completed attempt, or cancel and start a new sign-in if needed. A tunnel outage does not interrupt existing game connections or silent renewal. Restore the tunnel and start a fresh browser sign-in if the callback expired.
