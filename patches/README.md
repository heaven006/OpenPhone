# Patches

Patch stacks live under directories named after Android repo paths with `/`
replaced by `_`.

Examples:

```text
patches/frameworks_base/
patches/frameworks_native/
patches/packages_apps_Settings/
patches/packages_apps_SystemUI/
patches/vendor_lineage/
patches/build_make/
```

`scripts/apply-patches.sh` maps these directories back to Android checkout
paths and applies `*.patch` files with `git am`.

Promote a patch stack to an OpenPhone fork when it becomes too large or active
to maintain as patch files.
