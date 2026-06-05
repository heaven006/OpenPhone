# OpenPhone Model Broker

This is the first reference implementation of the OpenPhone model broker.

The phone should use broker mode for real development runs so provider API keys
stay off-device. The assistant sends OpenAI-shaped requests to this service
with a short-lived OpenPhone session token. The broker validates the token,
enforces coarse request limits, forwards the request to the provider, and
returns the provider response without logging request bodies.

## Endpoints

- `GET /healthz`
- `POST /v1/session_tokens`
- `POST /v1/responses`
- `POST /v1/audio/transcriptions`

`POST /v1/session_tokens` requires an admin bearer token and returns a signed,
expiring session token for a device or development subject:

```text
Authorization: Bearer <openphone-admin-token>
```

The model `POST` endpoints require:

```text
Authorization: Bearer <openphone-session-token>
```

## Environment

```text
OPENAI_API_KEY=sk-...
OPENPHONE_BROKER_TOKEN_SECRET='use-a-long-random-secret'
OPENPHONE_BROKER_ADMIN_TOKENS=admin-token-1,admin-token-2
OPENPHONE_BROKER_HOST=127.0.0.1
OPENPHONE_BROKER_PORT=8787
OPENPHONE_BROKER_MAX_BODY_BYTES=15728640
OPENPHONE_BROKER_MAX_BYTES_PER_MINUTE=62914560
OPENPHONE_BROKER_MAX_IMAGES_PER_REQUEST=1
OPENPHONE_BROKER_REQUIRE_OPENPHONE_METADATA=true
OPENPHONE_BROKER_REJECT_SENSITIVE_SCREEN=true
OPENPHONE_BROKER_PROVIDER_MAX_RETRIES=1
OPENPHONE_BROKER_PROVIDER_RETRY_BACKOFF_SECONDS=0.25
OPENPHONE_BROKER_RATE_LIMIT_PER_MINUTE=30
OPENPHONE_BROKER_TOKEN_MAX_TTL_SECONDS=3600
OPENPHONE_BROKER_ATTESTATION_MAX_SKEW_SECONDS=300
OPENPHONE_BROKER_DEVICE_REGISTRY=services/model-broker/devices.example.json
OPENPHONE_BROKER_PROVIDER_REGISTRY=services/model-broker/providers.example.json
OPENPHONE_BROKER_ALLOWED_RESPONSE_MODELS=gpt-5.5
OPENPHONE_BROKER_AUDIT_LOG=/var/log/openphone/model-broker.jsonl
OPENPHONE_DEVICE_PIXEL9A_DEV_SECRET='per-device-random-secret'
```

For quick local smoke tests, static tokens are also supported:

```text
OPENPHONE_BROKER_SESSION_TOKENS=dev-token-1,dev-token-2
```

## Run

```bash
python3 services/model-broker/openphone_model_broker.py
```

## Deploy

For a first hosted development deployment, use the hardened systemd unit and
environment/reverse-proxy templates under `services/model-broker/deploy/`.

Keep the broker bound to `127.0.0.1`, put it behind a TLS-terminating reverse
proxy, and keep provider/admin secrets only in `/etc/openphone/model-broker.env`.
See `deploy/README.md` for install commands and operational notes.

Render the nginx/certbot setup for a hosted broker with:

```bash
scripts/setup-model-broker-tls.sh --domain broker.example.com --email ops@example.com
```

Rotate broker token/admin secrets with:

```bash
scripts/rotate-model-broker-secrets.sh --env-file /etc/openphone/model-broker.env
```

Rotate the provider key after creating a replacement in the provider console:

```bash
scripts/rotate-model-broker-secrets.sh \
  --env-file /etc/openphone/model-broker.env \
  --provider-key sk-new-provider-key
```

## Smoke Test

Run the dependency-free broker smoke test before changing the broker or
assistant broker transport:

```bash
./scripts/smoke-test-model-broker.sh
```

The smoke test starts a local broker with dummy provider credentials and only
exercises requests that fail before provider forwarding. It verifies health,
authorization failure, signed token minting and acceptance, malformed JSON
rejection, provider-registry-backed Responses API model allowlisting,
device-registry-backed subject rejection, request-size and byte-rate limits,
transcription content-type enforcement, privacy-policy rejection for missing
OpenPhone metadata, sensitive-screen flags, excessive image count, audit event
writing, provider retry on transient upstream 429/5xx failures, and that
request-body contents do not appear in audit/server logs.

## Mint a Development Session Token

Preferred local/server path for subjects without an attestation secret:

```bash
curl -sS http://127.0.0.1:8787/v1/session_tokens \
  -H 'Authorization: Bearer admin-token-1' \
  -H 'Content-Type: application/json' \
  --data '{"subject":"pixel-9a-dev","ttl_seconds":3600}'
```

CLI fallback:

```bash
OPENPHONE_BROKER_TOKEN_SECRET='use-a-long-random-secret' \
  python3 services/model-broker/openphone_model_broker.py \
  --mint-token --subject pixel-9a-dev --ttl-seconds 3600
```

Put the resulting token in the assistant's broker session-token field. Signed
tokens use a small `op1` HMAC format with an expiry timestamp, subject, nonce,
and signature. The broker accepts both signed tokens and static development
tokens, but signed expiring tokens should be the default for real development
runs.

When a device registry entry contains `attestation_secret_env`, the token
issuer requires a development device proof before minting a session token. The
request must include `attestation_timestamp`, `attestation_nonce`, and
`attestation_signature`, where the signature is hex HMAC-SHA256 over:

```text
<subject>.<attestation_timestamp>.<attestation_nonce>
```

using the secret stored in the referenced environment variable. The broker
rejects missing, stale, or invalid proofs and records the audit outcome as
`attestation_required`, `attestation_expired`, or `attestation_invalid`.
This is not hardware-backed attestation; it is an early fail-closed development
hook so hosted token issuance is not only protected by the admin token.

## Audit and Provider Limits

`OPENPHONE_BROKER_DEVICE_REGISTRY` points at a JSON registry of subjects that
may receive signed session tokens. Registry entries can optionally include
`attestation_secret_env` to require the HMAC proof described above for that
subject. When the variable is unset, token issuance is unrestricted for local
bringup. When it is set, `/v1/session_tokens` fails closed for unknown
subjects.

`OPENPHONE_BROKER_PROVIDER_REGISTRY` points at a JSON registry describing the
development provider endpoints and allowed Responses API models. The included
`providers.example.json` is the default shape to copy for hosted development
deployments. `OPENPHONE_BROKER_ALLOWED_RESPONSE_MODELS` can still override the
registry model list for local testing.

The broker writes structured JSONL audit events for request outcomes. Audit
events include endpoint, token subject, client IP, status, body size, model
name when available, and latency. They intentionally do not include request or
response bodies.

Set `OPENPHONE_BROKER_ALLOWED_RESPONSE_MODELS` to a comma-separated allowlist
to block unexpected Responses API models before provider forwarding. Empty
means allow every model, which is useful for local bringup but should not be
used for hosted development services.

`OPENPHONE_BROKER_MAX_BODY_BYTES` caps a single request.
`OPENPHONE_BROKER_MAX_BYTES_PER_MINUTE` caps aggregate request bytes per
client/token subject over the rate-limit window. This protects hosted brokers
from a valid token repeatedly uploading screenshots or audio until the count
rate limit catches it.

For hosted development brokers, keep
`OPENPHONE_BROKER_REQUIRE_OPENPHONE_METADATA=true` so Responses requests must
carry OpenPhone task metadata from the assistant. Keep
`OPENPHONE_BROKER_REJECT_SENSITIVE_SCREEN=true` so requests marked with
`sensitive_input_visible` or `account_or_payment_hint_visible` are rejected
before provider forwarding. `OPENPHONE_BROKER_MAX_IMAGES_PER_REQUEST` limits
how many screenshots/images one Responses request may carry; the assistant v1
loop is expected to send one current screenshot per model step.

`OPENPHONE_BROKER_PROVIDER_MAX_RETRIES` and
`OPENPHONE_BROKER_PROVIDER_RETRY_BACKOFF_SECONDS` control short retries for
transient provider failures. The broker retries 429/5xx provider errors and
records `provider_attempts` in the audit event without logging request or
response bodies.

This service is intentionally small and dependency-free for early development.
Before production use it still needs hardware-backed device attestation,
automated admin-token/provider-secret rotation, and stronger abuse controls.
