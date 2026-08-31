package com.deskforge.app.engine

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Integrity result for the separately executed runtime shipped in the signed application. */
internal sealed interface ProotRuntimeStatus {
    data object Verified : ProotRuntimeStatus
    data object Unavailable : ProotRuntimeStatus
}

internal object ProotRuntimeIntegrity {
    private val checksumPattern = Regex("^[a-f0-9]{64}$")

    fun verify(executable: File, expectedSha256: String, expectedSizeBytes: Long): ProotRuntimeStatus {
        if (!checksumPattern.matches(expectedSha256) || expectedSha256.all { it == '0' }) {
            return ProotRuntimeStatus.Unavailable
        }
        if (expectedSizeBytes <= 0 || !executable.isFile || !executable.canExecute()) {
            return ProotRuntimeStatus.Unavailable
        }
        if (executable.length() != expectedSizeBytes) return ProotRuntimeStatus.Unavailable

        val actual = runCatching {
            MessageDigest.getInstance("SHA-256").run {
                executable.inputStream().buffered().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        update(buffer, 0, count)
                    }
                }
                digest()
            }
        }.getOrElse { return ProotRuntimeStatus.Unavailable }
        val expected = expectedSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return if (MessageDigest.isEqual(actual, expected)) {
            ProotRuntimeStatus.Verified
        } else {
            ProotRuntimeStatus.Unavailable
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}

/** Owns the app-private scratch directory used by the separately executed PRoot process. */
internal class ProotRuntimeStorage(private val directory: File) {
    fun prepare(): File = try {
        if (Files.isSymbolicLink(directory.toPath())) {
            throw IllegalStateException("The PRoot temporary directory is not trustworthy")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Unable to create the PRoot temporary directory")
        }
        if (!directory.isDirectory) {
            throw IllegalStateException("The PRoot temporary path is not a directory")
        }
        cleanupChildren()
        // Android confines codeCacheDir to the app UID; retain owner-only directory access.
        directory.setReadable(false, false)
        directory.setWritable(false, false)
        directory.setExecutable(false, false)
        directory.setReadable(true, true)
        directory.setWritable(true, true)
        directory.setExecutable(true, true)
        directory.canonicalFile
    } catch (failure: IOException) {
        throw IllegalStateException("Unable to prepare the PRoot temporary directory", failure)
    }

    fun cleanup() {
        // Cleanup is best-effort so shutdown state is never replaced by a cache I/O failure.
        runCatching {
            if (directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) cleanupChildren()
        }
    }

    private fun cleanupChildren() {
        directory.listFiles()?.forEach(::deleteWithoutFollowingLinks)
    }

    private fun deleteWithoutFollowingLinks(entry: File) {
        Files.walkFileTree(
            entry.toPath(),
            object : SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: java.nio.file.Path,
                    failure: java.io.IOException?,
                ): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
