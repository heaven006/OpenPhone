PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/products/openphone_arm64.mk \
    $(LOCAL_DIR)/products/openphone_tegu_smoke.mk \
    $(LOCAL_DIR)/products/openphone_tegu.mk

COMMON_LUNCH_CHOICES := \
    openphone_arm64-eng \
    openphone_arm64-bp2a-eng \
    openphone_arm64-bp2a-userdebug \
    openphone_tegu_smoke-bp2a-userdebug \
    openphone_tegu-bp2a-userdebug
