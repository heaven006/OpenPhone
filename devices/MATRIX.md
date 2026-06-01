# Device Matrix

| Device | Codename | State | Upstream | Notes |
| --- | --- | --- | --- | --- |
| Generic ARM64 product | `openphone_arm64` | bringup | LineageOS `lineage-23.2` | Build bootstrap target, not a supported phone. |
| Google Pixel 9a | `tegu` | verified bringup, agent validation in progress | LineageOS `lineage-23.2` | First physical target. Unlocked, flashed, and booted full `openphone_tegu`; assistant package and `openphone_agent` framework service verified over ADB. Current local artifact is the v51 model-disclosure/sepolicy OTA, with physical sideload/runtime validation pending because ADB currently lists the device but closes shell sessions. |
