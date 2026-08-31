package com.deskforge.app

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotRuntimeSmokeTest {
    @Test
    fun packagedRuntimeExecutesGuestProcess() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executable = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        assertTrue(executable.canExecute())
        assertTrue(loader.canExecute())
        val runtimeDirectory = File(context.codeCacheDir, "proot-smoke").apply {
            deleteRecursively()
            assertTrue(mkdirs())
        }

        try {
            val version = execute(executable, loader, runtimeDirectory, "--version")
            assertEquals(0, version.exitCode)
            assertTrue(version.output.contains(BuildConfig.PROOT_VERSION))

            val guest = execute(executable, loader, runtimeDirectory, "-r", "/", "/system/bin/true")
            assertEquals(guest.output, 0, guest.exitCode)
        } finally {
            runtimeDirectory.deleteRecursively()
        }
        assertFalse(runtimeDirectory.exists())
    }

    private fun execute(
        executable: File,
        loader: File,
        runtimeDirectory: File,
        vararg arguments: String,
    ): Result {
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = runtimeDirectory.absolutePath
            }
            .start()
        val completed = process.waitFor(20, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("PRoot command timed out", completed)
        return Result(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }

    private data class Result(val exitCode: Int, val output: String)
}
