package com.deskforge.app.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetIntegrityTest {
    @Test
    fun acceptsMatchingSha256Declaration() {
        val directory = Files.createTempDirectory("deskforge-integrity")
        val payload = directory.resolve("rootfs.tar.gz").toFile().apply { writeText("verified payload") }
        val checksum = directory.resolve("rootfs.tar.gz.sha256").toFile().apply {
            writeText("3aac0a1146ffe55bac7c05f61401fb1e7e4e6a94110b91585c646fe8cf745f28  rootfs.tar.gz\n")
        }

        assertEquals(
            "3aac0a1146ffe55bac7c05f61401fb1e7e4e6a94110b91585c646fe8cf745f28",
            AssetIntegrity.verifySha256(payload, checksum),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedPayload() {
        val directory = Files.createTempDirectory("deskforge-integrity")
        val payload = directory.resolve("rootfs.tar.gz").toFile().apply { writeText("unexpected payload") }
        val checksum = directory.resolve("rootfs.tar.gz.sha256").toFile().apply {
            writeText("3f387f5df67c01f6834246e9e6c1568c1cc3b66e50e44a8dd081ec588ea89e29  rootfs.tar.gz\n")
        }

        AssetIntegrity.verifySha256(payload, checksum)
    }
}
