# StudyOne Phase25 — Optional OAuth MCP Resource Gateway (Canary)

## Implemented (developer-only)

- **OAuth protected resource endpoint**: When explicitly configured, exposes `GET /.well-known/oauth-protected-resource/mcp` (also root fallback) with the canonical `resource`, external `authorization_servers`, and `studyone.read`/`studyone.propose` scopes. Missing MCP authentication gets a `401 WWW-Authenticate` with RFC 9728 `resource_metadata`.
- **Externally signed RS256 access tokens**: Validates a token using an **operator-pinned public JWKS** loaded from a local file at startup. Requires issuer, a **single exact MCP audience**, `sub`, `iat`, `nbf`, `exp`, and **max one-hour lifetime**. RSA public keys only; rejects private JWK material, unknown/duplicate key IDs, non-RS256 algorithms, malformed tokens, expired/forged credentials.
- **Explicit identity mapping**: An authenticated person is not auto-mapped from `email` or a display name. A server operator explicitly links the token's external issuer+`sub` to a StudyOne account. Reassignment to another account is denied. Unlink/disabled account blocks access.
- **Scope-separated tools**: `studyone.read` allows reading snapshot/changefeed/pending drafts. Adding `studyone.propose` permits proposal creation (still **not an active task**). Read-only tokens do not list the proposal tool. Only existing device `read-write` credentials can approve a proposal through `/v2`. OAuth bearer tokens are denied on the raw `/v2` API.
- **Off by default**: Without `--oauth-config`, the existing manually issued StudyOne device/MCP tokens still behave as before; JWTs and discovery are not accepted. There is no provider hostname hardcoded into the repository, and no live connections.

## Setup requirements (operator action, not performed in this PR)

Prepare a **real external OAuth 2.1-compatible authorization server** that handles authorization code + PKCE S256, user consent, refresh/revocation and discovery. Configure its access tokens specifically for the StudyOne resource, not an OIDC ID token. An administrator must receive the **public signing keys** from a trusted, authenticated issuer channel and store only public RSA JWK entries in `/secure/provider-public-jwks.json`. JWT private keys, bearer tokens and user login credentials must never be placed in this repo.

Install the optional verifier:

```sh
python -m pip install -r server/requirements-oauth.txt
```

Create an operator-local `/secure/studyone-oauth.json` based on `server/oauth-resource.example.json`, replacing `issuer`, `resource` (canonical public `https://.../mcp` endpoint), and absolute `jwksFile`.

Explicitly associate an externally authenticated issuer subject (`sub`, **not email**):

```sh
python server/private_sync.py link-oauth --oauth-config /secure/studyone-oauth.json --account YOUR_STUDYONE_ACCOUNT_UUID --subject EXTERNAL_IDP_SUB
python server/private_sync.py unlink-oauth --oauth-config /secure/studyone-oauth.json --subject EXTERNAL_IDP_SUB
```

When operator-provisioned TLS, DNS, reverse proxy, firewall, and OIDC provider are ready:

```sh
python server/private_sync.py serve --cert /secure/fullchain.pem --key /secure/privkey.pem --oauth-config /secure/studyone-oauth.json
```

This CLI still binds **127.0.0.1:9443** by default, not the public internet. Public HTTPS needs separate security review and an explicit deployment decision.

## Automated QA

- Real ephemeral **HTTPS** server with a generated local certificate and synthetic **RSA private key**, pinned public JWKS and fabricated accounts
- RFC 9728 discovery and `WWW-Authenticate` / HSTS / no-store headers
- Cross-account isolation, rejected token on `/v2`, scope-specific read and proposal tool permissions
- User's device-approved draft transition; assistant cannot approve its own pending plan
- Tampered signature, wrong issuer, wrong audience (including multi-audience), expiry, unlinked/revoked account, symmetric or private-key JWKS
- Existing account/backend, migrations, sync and recovery regression suite

## Significant limitations / release blockers

**This is not a completed ChatGPT connector or a deployed OAuth login.** StudyOne does not issue OAuth tokens or show a login/consent UI; it verifies optional, externally signed access tokens when an operator configures a trusted external issuer. The externally provided issuer/JWKS configuration needs a production key-rotation procedure and its authentication server's discovery/security assessment. Signed JWTs remain usable until `exp` unless the StudyOne issuer-subject account link is explicitly revoked or the account is disabled. The gateway does not implement per-JWT `jti` revocation.

No public domain, TLS certificate, OAuth provider, account link, token or user record was provisioned here. OpenAI/ChatGPT connector registration and real consent testing, device token storage, synchronization conflict UX, rate limiting, operational monitoring, external backup, penetration testing, iOS/Android/Windows live-device tests and signed release remain open.
