# OpenPhone product for Google Pixel 9a.
#
# This target inherits the upstream LineageOS device product and layers
# OpenPhone's privileged assistant, framework contracts, policy seed, and build
# properties on top.

# Lineage envsetup normally derives this from lineage_<device> products. The
# OpenPhone product keeps its own name, so set it explicitly before inheriting
# the Lineage product to keep Lineage/AOSP product conditionals consistent.
LINEAGE_BUILD := tegu

$(call inherit-product, device/google/tegu/lineage_tegu.mk)
$(call inherit-product, vendor/openphone/products/openphone_common.mk)

PRODUCT_BUILD_BOOT_IMAGE := true
PRODUCT_BUILD_INIT_BOOT_IMAGE := true
PRODUCT_BUILD_VENDOR_BOOT_IMAGE := true
# Tegu's kernel prebuilts currently ship the required DTB inside the prebuilt
# vendor_kernel_boot.img, not as standalone *.dtb files. Rebuilding this image
# from target-files creates a vendor_kernel_boot without a DTB and the Pixel 9a
# falls back to fastboot before Android starts. Keep the known-good upstream
# vendor_kernel_boot in place until OpenPhone provides a complete DTB source or
# switches to copying the prebuilt image.
PRODUCT_BUILD_VENDOR_KERNEL_BOOT_IMAGE := false
PRODUCT_BUILD_VBMETA_IMAGE := true
PRODUCT_BUILD_SUPER_PARTITION := true
PRODUCT_BUILD_SUPER_EMPTY_IMAGE := true

PRODUCT_BUILD_SYSTEM_IMAGE := true
PRODUCT_BUILD_SYSTEM_EXT_IMAGE := true
PRODUCT_BUILD_PRODUCT_IMAGE := true
PRODUCT_BUILD_VENDOR_IMAGE := true
PRODUCT_BUILD_SYSTEM_DLKM_IMAGE := true
PRODUCT_BUILD_VENDOR_DLKM_IMAGE := true

PRODUCT_NAME := openphone_tegu
PRODUCT_DEVICE := tegu
PRODUCT_MODEL := OpenPhone Pixel 9a
PRODUCT_BRAND := OpenPhone
PRODUCT_MANUFACTURER := OpenPhoneOS
