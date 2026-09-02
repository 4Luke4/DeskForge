package com.deskforge.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.engine.FedoraPayloadManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FedoraPayloadManifestTest {
    @Test
    fun acceptsOrderedBoundedParts() {
        val manifest = FedoraPayloadManifest.parse(manifestJson())

        assertEquals("fedora-xfce-44", manifest.distroId)
        assertEquals(2, manifest.workspaceIntegrationVersion)
        assertEquals(1, manifest.parts.size)
        assertEquals("fedora_xfce_44", manifest.parts.single().packName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPackThatDoesNotMatchPartPosition() {
        FedoraPayloadManifest.parse(manifestJson(packName = "fedora_xfce_44_1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedExpandedSize() {
        FedoraPayloadManifest.parse(manifestJson(uncompressedSize = 25L * 1024L * 1024L * 1024L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedGraphicsPackageIdentity() {
        FedoraPayloadManifest.parse(manifestJson().replace(
            "glx-utils-9.0.0-11.fc44.aarch64",
            "../../untrusted",
        ))
    }

    private fun manifestJson(
        packName: String = "fedora_xfce_44",
        uncompressedSize: Long = 11,
    ): String =
        """
        {
          "schemaVersion": 4,
          "distroId": "fedora-xfce-44",
          "release": "44",
          "desktopHostVersion": "1.16.2-4.fc44",
          "workspaceIntegrationVersion": 2,
          "audioHostPackages": ["pipewire-1.6.2-1.fc44.aarch64"],
          "graphicsHostPackages": ["glx-utils-9.0.0-11.fc44.aarch64"],
          "archiveSha256": "${"a".repeat(64)}",
          "archiveSizeBytes": 10,
          "uncompressedSizeBytes": $uncompressedSize,
          "parts": [{
            "packName": "$packName",
            "fileName": "rootfs.part00",
            "sizeBytes": 10,
            "sha256": "${"b".repeat(64)}"
          }]
        }
        """.trimIndent()
}
