# OpenPhone smoke product for Google Pixel 9a.
#
# This target deliberately keeps the runtime as close to upstream LineageOS as
# possible. It is used to prove that product identity/properties can boot before
# layering privileged apps and framework services back in.

LINEAGE_BUILD := tegu

$(call inherit-product, device/google/tegu/lineage_tegu.mk)

# Tegu's kernel prebuilts currently ship the required DTB inside the prebuilt
# vendor_kernel_boot.img, not as standalone *.dtb files. Rebuilding this image
# from target-files creates a vendor_kernel_boot without a DTB and the Pixel 9a
# falls back to fastboot before Android starts. Keep the known-good upstream
# vendor_kernel_boot in place until OpenPhone provides a complete DTB source or
# switches to copying the prebuilt image.
PRODUCT_BUILD_VENDOR_KERNEL_BOOT_IMAGE := false

PRODUCT_NAME := openphone_tegu_smoke
PRODUCT_DEVICE := tegu
PRODUCT_MODEL := OpenPhone Smoke Pixel 9a
PRODUCT_BRAND := OpenPhone
PRODUCT_MANUFACTURER := OpenPhoneOS

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.openphone.version=0.1.0-smoke \
    ro.openphone.source_available=true \
    ro.openphone.commercial_license_required=true \
    ro.openphone.smoke_build=true
