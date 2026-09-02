package com.deskforge.app.engine

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.deskforge.app.BuildConfig
import com.deskforge.app.model.SessionFailure
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Downloads every immutable Fedora part and activates the assembled workspace transactionally. */
class FedoraAssetInstaller(
    context: Context,
    private val manager: AssetPackManager = AssetPackManagerFactory.getInstance(context.applicationContext),
    private val extractor: SafeTarExtractor = SafeTarExtractor(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val applicationContext = context.applicationContext
    private val workspaceStore = FedoraWorkspaceStore(
        File(applicationContext.filesDir, DISTRO_DIRECTORY),
        BuildConfig.FEDORA_WORKSPACE_INTEGRATION_VERSION,
    )
    private val progressByPack = mutableMapOf<String, Pair<Long, Long>>()
    private var listener: AssetPackStateUpdateListener? = null
    private var installationRunning = false

    fun workspaceStatus(): WorkspaceStatus = workspaceStore.activeRootfs()?.let { rootfs ->
        WorkspaceStatus.Installed(rootfs.absolutePath)
    } ?: if (workspaceStore.updateRequired()) {
        WorkspaceStatus.UpdateRequired
    } else {
        WorkspaceStatus.Missing
    }

    fun install(onEvent: (InstallEvent) -> Unit) {
        if (tryInstallAvailable(onEvent)) return

        listener?.let(manager::unregisterListener)
        progressByPack.clear()
        val newListener = AssetPackStateUpdateListener { state -> handleState(state, onEvent) }
        listener = newListener
        manager.registerListener(newListener)
        manager.fetch(PACK_NAMES).addOnFailureListener { failure ->
            Log.e(TAG, "Play Asset Delivery request failed", failure)
            unregisterListener()
            onEvent(InstallEvent.Failed(SessionFailure.INSTALL_FAILED))
        }
    }

    fun close() {
        unregisterListener()
        executor.shutdown()
    }

    fun showDownloadConfirmation(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean =
        manager.showConfirmationDialog(launcher)

    private fun handleState(state: AssetPackState, onEvent: (InstallEvent) -> Unit) {
        if (state.name() !in PACK_NAMES) return
        progressByPack[state.name()] = state.bytesDownloaded() to state.totalBytesToDownload()
        val total = progressByPack.values.sumOf { it.second }
        val downloaded = progressByPack.values.sumOf { it.first }
        val fraction = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
        when (state.status()) {
            AssetPackStatus.PENDING, AssetPackStatus.DOWNLOADING, AssetPackStatus.TRANSFERRING ->
                onEvent(InstallEvent.Progress(fraction.coerceIn(0f, 1f)))
            AssetPackStatus.WAITING_FOR_WIFI, AssetPackStatus.REQUIRES_USER_CONFIRMATION ->
                onEvent(InstallEvent.WaitingForWifi)
            AssetPackStatus.COMPLETED -> tryInstallAvailable(onEvent)
            AssetPackStatus.CANCELED, AssetPackStatus.FAILED -> {
                Log.e(TAG, "Asset pack ${state.name()} failed with code ${state.errorCode()}")
                failDownload(onEvent)
            }
        }
    }

    private fun tryInstallAvailable(onEvent: (InstallEvent) -> Unit): Boolean {
        val primaryAssets = manager.getPackLocation(PRIMARY_PACK)?.assetsPath() ?: return false
        val manifestFile = File(primaryAssets, PAYLOAD_MANIFEST)
        if (!manifestFile.isFile) return false
        val manifest = runCatching { FedoraPayloadManifest.parse(manifestFile.readText()) }
            .getOrElse { failure ->
                Log.e(TAG, "Fedora payload manifest validation failed", failure)
                failDownload(onEvent)
                return true
            }
        if (manifest.workspaceIntegrationVersion != BuildConfig.FEDORA_WORKSPACE_INTEGRATION_VERSION) {
            Log.e(TAG, "Fedora payload integration version does not match this application")
            failDownload(onEvent)
            return true
        }
        val parts = manifest.parts.map { part ->
            val assets = manager.getPackLocation(part.packName)?.assetsPath() ?: return false
            File(assets, part.fileName)
        }
        synchronized(this) {
            if (installationRunning) return true
            installationRunning = true
        }
        unregisterListener()
        unpack(manifest, parts, onEvent)
        return true
    }

    private fun unpack(
        manifest: FedoraPayloadManifest,
        parts: List<File>,
        onEvent: (InstallEvent) -> Unit,
    ) {
        executor.execute {
            try {
                val archiveDigest = MessageDigest.getInstance("SHA-256")
                manifest.parts.zip(parts).forEach { (declaration, part) ->
                    AssetIntegrity.verifySha256(part, declaration.sha256, declaration.sizeBytes)
                    part.inputStream().buffered().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            archiveDigest.update(buffer, 0, count)
                        }
                    }
                }
                val archiveSha256 = archiveDigest.digest().toHex()
                require(archiveSha256 == manifest.archiveSha256) { "Fedora archive checksum validation failed" }
                require(
                    StatFs(applicationContext.filesDir.absolutePath).availableBytes >=
                        manifest.uncompressedSizeBytes + STORAGE_MARGIN_BYTES,
                ) { "Insufficient storage for the Fedora workspace update" }

                val destination = workspaceStore.destination(archiveSha256)
                if (!destination.exists()) {
                    val streams = parts.map(File::inputStream)
                    SequenceInputStream(Collections.enumeration(streams)).use { combined ->
                        GZIPInputStream(combined.buffered()).use { input ->
                            extractor.extractAtomically(input, destination.toPath()) { stagedRootfs ->
                                require(stagedRootfs.resolve("usr/bin/startxfce4").toFile().isFile)
                                require(stagedRootfs.resolve("usr/bin/Xvnc").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/libexec/deskforge/desktop-session").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/libexec/deskforge/guest-session").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/bin/pipewire").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/bin/pipewire-pulse").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/bin/wireplumber").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/bin/pactl").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/bin/glxinfo").toFile().canExecute())
                                require(stagedRootfs.resolve("usr/lib64/dri/virtio_gpu_dri.so").toFile().isFile)
                                require(stagedRootfs.resolve("usr/lib64/dri/swrast_dri.so").toFile().isFile)
                                require(
                                    stagedRootfs.resolve(
                                        "etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf",
                                    ).toFile().isFile,
                                )
                                val marker = JSONObject()
                                    .put("schemaVersion", 4)
                                    .put("payloadSha256", archiveSha256)
                                    .put("desktopHostVersion", manifest.desktopHostVersion)
                                    .put("workspaceIntegrationVersion", manifest.workspaceIntegrationVersion)
                                    .put("audioHostPackages", manifest.audioHostPackages)
                                    .put("graphicsHostPackages", manifest.graphicsHostPackages)
                                stagedRootfs.resolve(FedoraWorkspaceStore.INSTALL_MARKER).toFile()
                                    .writeText(marker.toString())
                            }
                        }
                    }
                }
                workspaceStore.activate(
                    archiveSha256,
                    manifest.desktopHostVersion,
                    manifest.workspaceIntegrationVersion,
                )
                onEvent(InstallEvent.Installed(destination.absolutePath))
            } catch (failure: Exception) {
                Log.e(TAG, "Fedora workspace installation failed", failure)
                onEvent(InstallEvent.Failed(SessionFailure.INSTALL_FAILED))
            } finally {
                synchronized(this) { installationRunning = false }
            }
        }
    }

    private fun failDownload(onEvent: (InstallEvent) -> Unit) {
        unregisterListener()
        onEvent(InstallEvent.Failed(SessionFailure.INSTALL_FAILED))
    }

    private fun unregisterListener() {
        listener?.let(manager::unregisterListener)
        listener = null
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    sealed interface WorkspaceStatus {
        data object Missing : WorkspaceStatus
        data object UpdateRequired : WorkspaceStatus
        data class Installed(val rootfsPath: String) : WorkspaceStatus
    }

    sealed interface InstallEvent {
        data class Progress(val fraction: Float) : InstallEvent
        data object WaitingForWifi : InstallEvent
        data class Installed(val rootfsPath: String) : InstallEvent
        data class Failed(val reason: SessionFailure) : InstallEvent
    }

    private companion object {
        val PACK_NAMES = listOf(
            "fedora_xfce_44",
            "fedora_xfce_44_1",
            "fedora_xfce_44_2",
            "fedora_xfce_44_3",
        )
        const val PRIMARY_PACK = "fedora_xfce_44"
        const val PAYLOAD_MANIFEST = "payload-manifest.json"
        const val DISTRO_DIRECTORY = "distros/fedora-xfce-44"
        const val BUFFER_SIZE = 64 * 1024
        const val STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L
        const val TAG = "FedoraAssetInstaller"
    }
}
