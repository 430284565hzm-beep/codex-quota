# Account protocol and implementation sources

This personal, local Android client implements only authentication and the account operations used by the open-source Codex client. It does not submit model inference or ChatGPT conversation requests.

## Sources inspected

- [OpenAI Docs: Codex app-server](https://learn.chatgpt.com/docs/app-server): authentication, account quota fields, reset credits, explicit consumption, idempotency, and the requirement to read quotas after consuming a credit.
- [OpenAI Docs: Authentication](https://learn.chatgpt.com/docs/auth): browser and device-code authentication, including the device-code setting.
- [OpenAI Docs: Sign in with ChatGPT registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): public-client PKCE, loopback callback, state and ID-token verification. The app presently follows Codex's existing personal-client account flow rather than the separately scoped Responses integration.
- [Codex source](https://github.com/openai/codex/tree/cda82a2c6853b484e0ba56d38f13902adfb2a6a1/codex-rs): `login/src/server.rs`, `login/src/device_code_auth.rs`, `login/src/auth/manager.rs`, `login/src/token_data.rs`, `backend-client/src/client.rs`, `backend-client/src/client/rate_limit_resets.rs`, and `backend-client/src/types.rs`.

These backend paths are implementation details of the personal Codex account client, not a promise of a stable general Android API.

## Browser authentication

The phone binds a listener only on `127.0.0.1:1455`, falling back to registered port `1457` when occupied (matching the Codex redirect allow-list), generates a fresh state, OIDC nonce and S256 PKCE verifier, and opens the system browser. The existing public Codex OAuth client identifier is used; it is not a client secret. The listener handles `/auth/callback`, validates state, exchanges an authorization code over HTTPS, and verifies the ID token's RS256 signature with OpenAI's published JWKS, issuer, audience, expiration and nonce. Account/workspace identity comes from the verified claims. A new login is committed only after those checks; canceled attempts cannot replace a session.

Identity scopes: `openid profile email offline_access`. Connector read/invoke scopes are not requested.

Device-code fallback follows `POST /api/accounts/deviceauth/usercode`, polls `POST /api/accounts/deviceauth/token` according to the service interval, then performs a PKCE code exchange using `https://auth.openai.com/deviceauth/callback`. Its timeout is 15 minutes.

Tokens remain in the phone's AES-GCM encrypted vault, with the key held by Android Keystore. Refresh is serialized and rotated credentials are persisted. HTTP redirects are disabled for token and account requests; credentials are sent only to the fixed HTTPS service host.

## Account operations

Base: `https://chatgpt.com/backend-api/wham`. Requests carry the locally authorized bearer and the selected `ChatGPT-Account-Id`.

| Operation | Request |
| --- | --- |
| Quotas and authoritative reset count | `GET /usage` |
| Optional credit details and expiry | `GET /rate-limit-reset-credits` |
| Explicit redemption | `POST /rate-limit-reset-credits/consume` |

Quota payload: `rate_limit.primary_window` and `secondary_window`, with `used_percent`, `limit_window_seconds`, and Unix seconds `reset_at`. Additional metered buckets are kept separate. Reset count is `rate_limit_reset_credits.available_count`; details can be absent or capped. Credit expiry strings are parsed as ISO-8601 as in the backend payload.

Redemption body: `redeem_request_id` (UUID), optional `credit_id`. Backend result codes are `reset`, `already_redeemed`, `nothing_to_reset`, and `no_credit`. The corresponding app-server RPC names and camelCase response names are a different transport layer; Android uses the backend JSON form inspected in the source.

Before sending a redemption, a local journal is committed for that account. A connection timeout or ambiguous response preserves the same UUID and credit ID. Definitive results clear it. Double submission is guarded. Each definitive result is followed by a quota read; counts and remaining percentages are never locally fabricated.

## Platform behavior

Foreground polling is configurable at 15, 30 or 60 seconds. Background reads use Android JobScheduler with a network requirement and a minimum 15-minute periodic interval; Android can delay them. Matching jobs are preserved across process creation and receiver callbacks: rescheduling the same ID can stop an active job and reset its due time. Widget providers also have a 30-minute system update fallback. This is polling, not server push.

Widget refresh clicks use a foreground-service pending intent for a brief `dataSync` service, not an Activity. User interaction with an app widget is an Android background-service start exemption. The service stops and removes its temporary notification after a read, with a timeout guard. It is never started automatically at boot. Concurrent foreground, service and job reads coalesce; callers receive the same completion. No background path redeems credits. Pending UI state clears on completion, error and process recreation.

Native quota refill animation starts only after confirmed redemption and a successful quota reread. The existing quota bars and percentages interpolate from their prior values to the returned values over 1.2 seconds, using the same decelerating cubic curve `(0.20, 0.65, 0.30, 1)` on Android and Windows. Missing or unchanged windows are not fabricated as full. Demo mode simulates this using demo data only.

Reset feedback preserves the original expanding halo and outward light points, and adds optional native confetti (1.6 seconds). There is no additional loading meter. A short click acknowledges confirmation; a gentle original synthesized chime plays only after successful redemption and a successful quota reread. Audio and confetti are independently switchable. Android predecodes the WAV resources with SoundPool, uses assistance-sonification audio attributes, honors silent/vibrate mode and system volume, and releases the player with the activity. Windows preloads embedded WAV files into SoundPlayer and uses asynchronous playback. Reduced-motion settings suppress celebration animations; the glass material and blur pipeline are unchanged. Tests never send a real redemption request and mute test audio.

Platform references: [JobScheduler replacement behavior](https://developer.android.com/reference/android/app/job/JobScheduler.html), [widget interaction and foreground service exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), and [Samsung application management](https://developer.samsung.com/mobile/app-management.html).

Both widget types use native RemoteViews, accessible text, and cached, antialiased material artwork. Narrow/tall sizes use vertical metrics. Their blur is based on an explicitly selected background image or the app's generated background; no screen capture, wallpaper permission bypass, or live launcher background read is used.

## Verification boundary

Build signature and Android startup are verified locally. Protocol inspection and a read-only authenticated quota GET are performed on the development computer without exporting credentials into the APK. Identity, retry and double-submit behavior use local test credentials and a fake account client on a clean Android emulator. No real reset credit is consumed. The user's phone must complete its own sign-in after installation; its manufacturer launcher and background scheduling remain device-specific.
