package com.deskforge.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskforge.app.engine.FedoraWorkspaceStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FedoraWorkspaceStoreTest {
    @Test
    fun activatesCompleteWorkspaceByDigest() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "workspace-store-${System.nanoTime()}")
        val store = FedoraWorkspaceStore(directory)
        val digest = "a".repeat(64)
        val rootfs = store.destination(digest)
        executable(rootfs, "usr/bin/startxfce4")
        executable(rootfs, "usr/bin/Xvnc")
        executable(rootfs, "usr/libexec/deskforge/desktop-session")
        File(rootfs, FedoraWorkspaceStore.INSTALL_MARKER).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        store.activate(digest, "1.16.2-4.fc44")

        assertEquals(rootfs.canonicalFile, store.activeRootfs()?.canonicalFile)
        directory.deleteRecursively()
    }

    @Test
    fun recognizesLegacyWorkspaceAsUpdateRequired() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "workspace-legacy-${System.nanoTime()}")
        executable(File(directory, "rootfs"), "usr/bin/startxfce4")
        File(directory, "rootfs/.deskforge-source-sha256").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }

        assertTrue(FedoraWorkspaceStore(directory).legacyUpdateRequired())
        directory.deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSymlinkedInstallationControlDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "workspace-symlink-${System.nanoTime()}").apply { mkdirs() }
        val externalTarget = File(context.cacheDir, "workspace-target-${System.nanoTime()}").apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "installations").toPath(), externalTarget.toPath())

        try {
            FedoraWorkspaceStore(directory).destination("a".repeat(64))
        } finally {
            File(directory, "installations").delete()
            directory.delete()
            externalTarget.deleteRecursively()
        }
    }

    private fun executable(root: File, relative: String) {
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText("fixture")
            setExecutable(true)
        }
    }
}
