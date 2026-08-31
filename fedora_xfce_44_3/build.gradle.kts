plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName = "fedora_xfce_44_3"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
