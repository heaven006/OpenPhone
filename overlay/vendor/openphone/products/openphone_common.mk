# Common OpenPhone product layer.

PRODUCT_BRAND := OpenPhone
PRODUCT_MANUFACTURER := OpenPhoneOS

PRODUCT_PACKAGES += \
    OpenPhoneAssistant

PRODUCT_COPY_FILES += \
    vendor/openphone/config/privapp-permissions-openphone.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-openphone.xml \
    vendor/openphone/config/sysconfig-openphone.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/sysconfig/sysconfig-openphone.xml \
    vendor/openphone/config/openphone_capabilities.json:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/openphone/capabilities.json \
    vendor/openphone/config/openphone_policy.json:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/openphone/policy.json

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.openphone.version=0.1.0-dev \
    ro.openphone.source_available=true \
    ro.openphone.commercial_license_required=true

