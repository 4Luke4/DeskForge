package com.deskforge.app.engine

import android.content.Context
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** Downloads the Play-hosted Fedora payload and activates it transactionally. */
class FedoraAssetInstaller(
    context: Context,
    private val manager: AssetPackManager = AssetPackManagerFactory.getInstance(context.applicationContext),
    private val extractor: SafeTarExtractor = SafeTarExtractor(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val applicationContext = context.applicationContext
    private var listener: AssetPackStateUpdateListener? = null

    fun install(onEvent: (InstallEvent) -> Unit) {
        manager.getPackLocation(PACK_NAME)?.let { location ->
            val assetsPath = location.assetsPath()
            if (assetsPath == null) {
                onEvent(InstallEvent.Failed("The Fedora asset pack does not expose file storage"))
            } else {
                unpack(assetsPath, onEvent)
            }
            return
        }

        listener?.let(manager::unregisterListener)
        val newListener = AssetPackStateUpdateListener { state -> handleState(state, onEvent) }
        listener = newListener
        manager.registerListener(newListener)
        manager.fetch(listOf(PACK_NAME)).addOnFailureListener { failure ->
            unregisterListener()
            onEvent(InstallEvent.Failed(failure.message ?: "Play Asset Delivery failed"))
        }
    }

    fun close() {
        unregisterListener()
        executor.shutdown()
    }

    private fun handleState(state: AssetPackState, onEvent: (InstallEvent) -> Unit) {
        if (state.name() != PACK_NAME) return
        val totalBytes = state.totalBytesToDownload()
        val progress = if (totalBytes > 0) {
            state.bytesDownloaded().toFloat() / totalBytes.toFloat()
        } else {
            0f
        }
        when (state.status()) {
            AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING ->
                onEvent(InstallEvent.Progress(progress.coerceIn(0f, 1f)))
            AssetPackStatus.WAITING_FOR_WIFI -> onEvent(InstallEvent.WaitingForWifi)
            AssetPackStatus.COMPLETED -> {
                unregisterListener()
                val location = manager.getPackLocation(PACK_NAME)
                val assetsPath = location?.assetsPath()
                if (assetsPath == null) {
                    onEvent(InstallEvent.Failed("Completed asset pack has no file-storage location"))
                } else {
                    unpack(assetsPath, onEvent)
                }
            }
            AssetPackStatus.CANCELED -> {
                unregisterListener()
                onEvent(InstallEvent.Failed("Fedora installation was canceled"))
            }
            AssetPackStatus.FAILED -> {
                unregisterListener()
                onEvent(InstallEvent.Failed("Play Asset Delivery error ${state.errorCode()}"))
            }
        }
    }

    private fun unpack(assetsPath: String, onEvent: (InstallEvent) -> Unit) {
        executor.execute {
            try {
                val archive = File(assetsPath, ROOTFS_ARCHIVE)
                require(archive.isFile) { "The Fedora asset pack does not contain $ROOTFS_ARCHIVE" }
                val verifiedSha256 = AssetIntegrity.verifySha256(
                    archive,
                    File(assetsPath, ROOTFS_CHECKSUM),
                )
                val destination = File(applicationContext.filesDir, ROOTFS_DIRECTORY).toPath()
                GZIPInputStream(archive.inputStream().buffered()).use { input ->
                    extractor.extractAtomically(input, destination) { stagedRootfs ->
                        // Provenance becomes visible in the same atomic move as the root filesystem.
                        stagedRootfs.resolve(INSTALL_MARKER).toFile().writeText("$verifiedSha256\n")
                    }
                }
                onEvent(InstallEvent.Installed(destination.toString()))
            } catch (failure: Exception) {
                onEvent(InstallEvent.Failed(failure.message ?: "Unable to install Fedora"))
            }
        }
    }

    private fun unregisterListener() {
        listener?.let(manager::unregisterListener)
        listener = null
    }

    sealed interface InstallEvent {
        data class Progress(val fraction: Float) : InstallEvent
        data object WaitingForWifi : InstallEvent
        data class Installed(val rootfsPath: String) : InstallEvent
        data class Failed(val message: String) : InstallEvent
    }

    private companion object {
        const val PACK_NAME = "fedora_xfce_44"
        const val ROOTFS_ARCHIVE = "rootfs.tar.gz"
        const val ROOTFS_CHECKSUM = "rootfs.tar.gz.sha256"
        const val ROOTFS_DIRECTORY = "distros/fedora-xfce-44/rootfs"
        const val INSTALL_MARKER = ".deskforge-source-sha256"
    }
}
