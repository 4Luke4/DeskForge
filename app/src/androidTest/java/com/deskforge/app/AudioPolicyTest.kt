package com.deskforge.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPolicyTest {
    @Test
    fun declaresOptionalMicrophoneAndRequiredForegroundServiceTypes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))

        val featureInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_CONFIGURATIONS.toLong()),
        ).reqFeatures.orEmpty().first { it.name == PackageManager.FEATURE_MICROPHONE }
        assertFalse(featureInfo.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0)

        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, DeskForgeSessionService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertTrue(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
        assertTrue(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
        assertTrue(serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        assertFalse(serviceInfo.exported)
    }
}
