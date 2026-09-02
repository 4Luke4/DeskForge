package com.deskforge.app.engine

import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Atomically selects a complete immutable workspace before retiring the previous installation. */
internal class FedoraWorkspaceStore(
    private val distroDirectory: File,
    private val expectedIntegrationVersion: Int,
) {
    private val installationsDirectory = File(distroDirectory, "installations")
    private val activeFile = File(distroDirectory, "active.json")
    private val legacyRootfs = File(distroDirectory, "rootfs")

    fun activeRootfs(): File? {
        if (!trustedControlDirectories() ||
            !Files.isRegularFile(activeFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            activeFile.length() > MAX_ACTIVE_FILE_BYTES
        ) {
            return null
        }
        return runCatching {
            val active = JSONObject(activeFile.readText())
            require(active.getInt("schemaVersion") == 2)
            require(active.getInt("workspaceIntegrationVersion") == expectedIntegrationVersion)
            val digest = active.getString("payloadSha256")
            require(DIGEST_PATTERN.matches(digest))
            installationRootfs(digest).takeIf { rootfs -> isLaunchable(rootfs, digest) }
        }.getOrNull()
    }

    fun updateRequired(): Boolean {
        if (!trustedControlDirectories() || activeRootfs() != null) return false
        if (isLegacyLaunchable(legacyRootfs)) return true
        if (!Files.isRegularFile(activeFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            activeFile.length() > MAX_ACTIVE_FILE_BYTES
        ) {
            return false
        }
        return runCatching {
            val active = JSONObject(activeFile.readText())
            val digest = active.getString("payloadSha256")
            DIGEST_PATTERN.matches(digest) && isLegacyIntegrationLaunchable(installationRootfs(digest))
        }.getOrDefault(false)
    }

    fun destination(payloadSha256: String): File {
        require(DIGEST_PATTERN.matches(payloadSha256))
        require(trustedControlDirectories()) { "The Fedora workspace directory is not trustworthy" }
        Files.createDirectories(installationsDirectory.toPath())
        return installationRootfs(payloadSha256)
    }

    fun activate(payloadSha256: String, desktopHostVersion: String, integrationVersion: Int) {
        require(trustedControlDirectories()) { "The Fedora workspace directory is not trustworthy" }
        require(integrationVersion == expectedIntegrationVersion)
        val rootfs = installationRootfs(payloadSha256)
        require(isLaunchable(rootfs, payloadSha256)) { "The prepared Fedora workspace is incomplete" }
        Files.createDirectories(distroDirectory.toPath())
        val temporary = File(distroDirectory, ".active-${System.nanoTime()}.json")
        val declaration = JSONObject()
            .put("schemaVersion", 2)
            .put("payloadSha256", payloadSha256)
            .put("desktopHostVersion", desktopHostVersion)
            .put("workspaceIntegrationVersion", integrationVersion)
            .toString()
        java.io.FileOutputStream(temporary).use { fileOutput ->
            val output = BufferedOutputStream(fileOutput)
            output.write(declaration.toByteArray())
            output.flush()
            // Persist the selector before the atomic rename so a crash cannot expose partial JSON.
            fileOutput.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            activeFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        cleanupInactive(payloadSha256)
    }

    private fun cleanupInactive(activeDigest: String) {
        installationsDirectory.listFiles()?.forEach { installation ->
            if (installation.name != activeDigest) runCatching { deleteWithoutFollowingLinks(installation) }
        }
        if (legacyRootfs.exists()) runCatching { deleteWithoutFollowingLinks(legacyRootfs) }
    }

    private fun installationRootfs(digest: String) = File(installationsDirectory, "$digest/rootfs")

    private fun isLaunchable(rootfs: File, payloadSha256: String): Boolean =
        rootfs.isDirectory &&
            File(rootfs, "usr/bin/startxfce4").isFile &&
            File(rootfs, "usr/bin/Xvnc").canExecute() &&
            File(rootfs, "usr/libexec/deskforge/desktop-session").canExecute() &&
            File(rootfs, "usr/libexec/deskforge/guest-session").canExecute() &&
            File(rootfs, "usr/bin/pipewire").canExecute() &&
            File(rootfs, "usr/bin/pipewire-pulse").canExecute() &&
            File(rootfs, "usr/bin/wireplumber").canExecute() &&
            File(rootfs, "usr/bin/pactl").canExecute() &&
            File(rootfs, "usr/bin/glxinfo").canExecute() &&
            File(rootfs, "usr/lib64/dri/virtio_gpu_dri.so").isFile &&
            File(rootfs, "usr/lib64/dri/swrast_dri.so").isFile &&
            File(rootfs, "etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf").isFile &&
            installMarkerMatches(rootfs, payloadSha256)

    private fun installMarkerMatches(rootfs: File, payloadSha256: String): Boolean {
        val marker = File(rootfs, INSTALL_MARKER)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            marker.length() > MAX_INSTALL_MARKER_BYTES
        ) {
            return false
        }
        return runCatching {
            val declaration = JSONObject(marker.readText())
            declaration.getInt("schemaVersion") == 4 &&
                declaration.getString("payloadSha256") == payloadSha256 &&
                declaration.getInt("workspaceIntegrationVersion") == expectedIntegrationVersion
        }.getOrDefault(false)
    }

    private fun isLegacyIntegrationLaunchable(rootfs: File): Boolean =
        rootfs.isDirectory && File(rootfs, "usr/bin/startxfce4").isFile &&
            File(rootfs, "usr/bin/Xvnc").canExecute() &&
            File(rootfs, "usr/libexec/deskforge/desktop-session").canExecute()

    private fun isLegacyLaunchable(rootfs: File): Boolean =
        rootfs.isDirectory && File(rootfs, "usr/bin/startxfce4").isFile &&
            File(rootfs, ".deskforge-source-sha256").isFile

    private fun trustedControlDirectories(): Boolean {
        val parent = distroDirectory.parentFile ?: return false
        return !Files.isSymbolicLink(parent.toPath()) &&
            !Files.isSymbolicLink(distroDirectory.toPath()) &&
            !Files.isSymbolicLink(installationsDirectory.toPath())
    }

    private fun deleteWithoutFollowingLinks(entry: File) {
        if (!Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(entry.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        const val INSTALL_MARKER = ".deskforge-install.json"
        private const val MAX_ACTIVE_FILE_BYTES = 4096L
        private const val MAX_INSTALL_MARKER_BYTES = 8192L
        private val DIGEST_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}
