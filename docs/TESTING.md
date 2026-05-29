# Testing

## Local Scaffold Check

Run this before attempting a full Android build:

```bash
./scripts/check.sh
```

It validates:

- Required project files exist.
- Shell scripts parse with `bash -n`.
- XML files parse when `xmllint` is available.
- JSON config and schema files parse when `python3` is available.

## Android Build Check

After installing Android build dependencies and `repo`:

```bash
./scripts/sync.sh
./scripts/apply-patches.sh
./scripts/build.sh openphone_arm64
```

The first full Android build is expected to expose integration issues. Fixing
those against the synced Lineage tree is part of Phase 1.

## Device Check

No physical device is supported until its `devices/<codename>.md` checklist is
complete.

