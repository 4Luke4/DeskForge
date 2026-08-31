plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName = "fedora_xfce_44_1"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
