# Security Policy

OpenPhone is an early developer preview project. Do not use current builds as a
daily-driver secure phone OS.

## Reporting Security Issues

Do not file public GitHub issues for vulnerabilities, leaked secrets, bypasses,
or exploitable device-control bugs.

Report privately to the project maintainer. Until a dedicated security address
is published, use the maintainer's existing private contact channel.

## Current Security Posture

- The bootloader on development devices is expected to be unlocked.
- Development builds are not production signed.
- Play Integrity compatibility is not a goal for early releases.
- The assistant and framework service are experimental.
- Production key management for model providers is not implemented.

## Secrets

Never commit:

- API keys,
- private SSH keys,
- signing keys,
- vendor credentials,
- personal device data,
- generated Android build outputs.
