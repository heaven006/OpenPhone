# Licensing

OpenPhone has to keep upstream and OpenPhone-owned code legally separate.

## OpenPhone-Owned Code

OpenPhone-owned code is intended to be source-available for non-commercial use.
Commercial use requires a separate written license from the OpenPhone project
owner.

The current [LICENSE](../LICENSE) is a temporary project notice. Before public
release, the project should adopt a complete source-available license text and
apply clear file headers.

## Upstream Code

OpenPhone does not relicense upstream code. AOSP, LineageOS, Linux kernel,
device trees, vendor extraction scripts, and third-party dependencies keep
their original licenses.

## GPL

GPL-covered components, especially kernel code, remain GPL-covered. Any
distributed GPL modifications must satisfy GPL source obligations. Do not put
non-commercial restrictions on GPL-covered code.

## Vendor Blobs

Vendor blobs may be proprietary. The project should prefer extraction scripts
unless redistribution is clearly permitted.

Every supported device should document:

- Blob source.
- Blob version.
- Whether blobs are hosted or extracted.
- Redistribution notes.

