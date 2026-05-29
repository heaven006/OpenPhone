# Generic OpenPhone ARM64 bootstrap product.
#
# This target validates the OpenPhone product layer before a physical device is
# selected. It is not a supported phone target.

$(call inherit-product, vendor/lineage/build/target/product/lineage_gsi_arm64.mk)
$(call inherit-product, vendor/openphone/products/openphone_common.mk)

PRODUCT_NAME := openphone_arm64
PRODUCT_DEVICE := generic_arm64
PRODUCT_MODEL := OpenPhone Generic ARM64
PRODUCT_ENFORCE_ARTIFACT_PATH_REQUIREMENTS :=
