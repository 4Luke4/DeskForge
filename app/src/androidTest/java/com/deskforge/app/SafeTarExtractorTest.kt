package com.deskforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.engine.SafeTarExtractor
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4::class)
class SafeTarExtractorTest {
    @Test(expected = IllegalArgumentException::class)
    fun traversalEntryIsRejected() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = File(context.cacheDir, "tar-test-${System.nanoTime()}")
        val destination = File(parent, "rootfs")

        try {
            SafeTarExtractor().extractAtomically(
                ByteArrayInputStream(tarWithFile("../escape", "blocked")),
                destination.toPath(),
            )
        } finally {
            assertFalse(File(parent.parentFile, "escape").exists())
            parent.deleteRecursively()
        }
    }

    private fun tarWithFile(name: String, content: String): ByteArray {
        val payload = content.toByteArray(StandardCharsets.UTF_8)
        val header = ByteArray(512)
        name.toByteArray(StandardCharsets.UTF_8).copyInto(header, endIndex = name.length)
        "0000644\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 100)
        "0000000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 108)
        "0000000\u0000".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 116)
        payload.size.toString(8).padStart(11, '0').plus('\u0000')
            .toByteArray(StandardCharsets.US_ASCII).copyInto(header, 124)
        // POSIX tar calculates the header checksum while treating this field as spaces.
        header.fill(' '.code.toByte(), fromIndex = 148, toIndex = 156)
        header[156] = '0'.code.toByte()
        val checksum = header.sumOf { it.toInt() and 0xff }
        checksum.toString(8).padStart(6, '0').plus("\u0000 ")
            .toByteArray(StandardCharsets.US_ASCII).copyInto(header, 148)
        return ByteArrayOutputStream().use { output ->
            output.write(header)
            output.write(payload)
            output.write(ByteArray((512 - payload.size % 512) % 512))
            output.write(ByteArray(1024))
            output.toByteArray()
        }
    }
}
