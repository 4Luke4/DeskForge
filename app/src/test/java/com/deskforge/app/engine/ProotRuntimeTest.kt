package com.deskforge.app.engine

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exact executable is verified`() {
        val executable = executableWith("verified runtime")

        assertEquals(
            ProotRuntimeStatus.Verified,
            ProotRuntimeIntegrity.verify(executable, sha256(executable), executable.length()),
        )
    }

    @Test
    fun `missing truncated and mismatched executables fail closed`() {
        val executable = executableWith("runtime")
        val expected = sha256(executable)

        assertEquals(
            ProotRuntimeStatus.Unavailable,
            ProotRuntimeIntegrity.verify(File(temporaryFolder.root, "missing"), expected, executable.length()),
        )
        assertEquals(
            ProotRuntimeStatus.Unavailable,
            ProotRuntimeIntegrity.verify(executable, expected, executable.length() + 1),
        )
        assertEquals(
            ProotRuntimeStatus.Unavailable,
            ProotRuntimeIntegrity.verify(executable, "f".repeat(64), executable.length()),
        )
    }

    @Test
    fun `runtime storage removes stale entries without following symlinks`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val staleDirectory = File(runtimeDirectory, "stale").apply { mkdir() }
        File(staleDirectory, "loader").writeText("stale")
        val outside = temporaryFolder.newFile("outside").apply { writeText("preserve") }
        Files.createSymbolicLink(File(runtimeDirectory, "outside-link").toPath(), outside.toPath())

        val prepared = ProotRuntimeStorage(runtimeDirectory).prepare()

        assertTrue(prepared.isDirectory)
        assertFalse(staleDirectory.exists())
        assertTrue(outside.isFile)
        assertTrue(File(prepared, "dev-shm").isDirectory)
        assertFalse(Files.isSymbolicLink(File(prepared, "dev-shm").toPath()))
    }

    private fun executableWith(content: String): File =
        temporaryFolder.newFile().apply {
            writeText(content)
            assertTrue(setExecutable(true, true))
        }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
