package com.deskforge.app.engine

import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Atomically selects a complete immutable workspace before retiring the previous installation. */
internal class FedoraWorkspaceStore(private val distroDirectory: File) {
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
            require(active.getInt("schemaVersion") == 1)
            val digest = active.getString("payloadSha256")
            require(DIGEST_PATTERN.matches(digest))
            installationRootfs(digest).takeIf(::isLaunchable)
        }.getOrNull()
    }

    fun legacyUpdateRequired(): Boolean =
        trustedControlDirectories() && isLegacyLaunchable(legacyRootfs) && activeRootfs() == null

    fun destination(payloadSha256: String): File {
        require(DIGEST_PATTERN.matches(payloadSha256))
        require(trustedControlDirectories()) { "The Fedora workspace directory is not trustworthy" }
        Files.createDirectories(installationsDirectory.toPath())
        return installationRootfs(payloadSha256)
    }

    fun activate(payloadSha256: String, desktopHostVersion: String) {
        require(trustedControlDirectories()) { "The Fedora workspace directory is not trustworthy" }
        val rootfs = installationRootfs(payloadSha256)
        require(isLaunchable(rootfs)) { "The prepared Fedora workspace is incomplete" }
        Files.createDirectories(distroDirectory.toPath())
        val temporary = File(distroDirectory, ".active-${System.nanoTime()}.json")
        val declaration = JSONObject()
            .put("schemaVersion", 1)
            .put("payloadSha256", payloadSha256)
            .put("desktopHostVersion", desktopHostVersion)
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

    private fun isLaunchable(rootfs: File): Boolean =
        rootfs.isDirectory &&
            File(rootfs, "usr/bin/startxfce4").isFile &&
            File(rootfs, "usr/bin/Xvnc").canExecute() &&
            File(rootfs, "usr/libexec/deskforge/desktop-session").canExecute() &&
            File(rootfs, INSTALL_MARKER).isFile

    private fun isLegacyLaunchable(rootfs: File): Boolean =
        rootfs.isDirectory && File(rootfs, "usr/bin/startxfce4").isFile &&
            File(rootfs, ".deskforge-source-sha256").isFile

    private fun trustedControlDirectories(): Boolean =
        distroDirectory.parentFile != null &&
            !Files.isSymbolicLink(distroDirectory.parentFile.toPath()) &&
            !Files.isSymbolicLink(distroDirectory.toPath()) &&
            !Files.isSymbolicLink(installationsDirectory.toPath())

    private fun deleteWithoutFollowingLinks(entry: File) {
        if (!Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(entry.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        const val INSTALL_MARKER = ".deskforge-install.json"
        private const val MAX_ACTIVE_FILE_BYTES = 4096L
        private val DIGEST_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}
