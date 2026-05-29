# Manifests

`scripts/sync.sh` initializes the Android tree from LineageOS and installs
`openphone.xml` as a local manifest.

Current default:

```text
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2 --git-lfs
```

Future OpenPhone forks should be added to `openphone.xml` as project overrides.
