package com.deskforge.app.engine

import android.system.Os
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Minimal USTAR extractor for trusted, checksum-verified distro payloads.
 *
 * The extractor still treats archive paths as hostile: it rejects traversal, special devices,
 * writes through symlink ancestors, and activation of a partially extracted root filesystem.
 */
class SafeTarExtractor {
    fun extractAtomically(
        input: InputStream,
        destination: Path,
        prepareActivation: (Path) -> Unit = {},
    ) {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "The destination already exists"
        }
        val parent = requireNotNull(destination.parent) { "The destination requires a parent" }
        Files.createDirectories(parent)
        val staging = parent.resolve(".${destination.fileName}.staging-${UUID.randomUUID()}")
        Files.createDirectory(staging)

        try {
            extract(BufferedInputStream(input), staging)
            // Callers can place provenance metadata inside the staged tree before it is visible.
            prepareActivation(staging)
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            deleteTree(staging)
            throw failure
        }
    }

    private fun extract(input: BufferedInputStream, root: Path) {
        val deferredLinks = mutableListOf<TarLink>()
        val pendingPax = mutableMapOf<String, String>()
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        val header = ByteArray(BLOCK_SIZE)
        while (true) {
            readFully(input, header, BLOCK_SIZE)
            if (header.all { it == 0.toByte() }) break
            require(hasValidHeaderChecksum(header)) { "Tar header checksum validation failed" }

            val headerName = parseName(header)
            val mode = parseOctal(header, 100, 8).toInt()
            val headerSize = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()

            if (type == 'L' || type == 'K' || type == 'x') {
                val metadata = readMetadata(input, headerSize)
                when (type) {
                    'L' -> pendingLongName = metadata.decodeToString().trimEnd('\u0000', '\n')
                    'K' -> pendingLongLink = metadata.decodeToString().trimEnd('\u0000', '\n')
                    'x' -> pendingPax.putAll(parsePax(metadata))
                }
                skipExactly(input, paddingFor(headerSize))
                continue
            }
            require(type != 'g') { "Global PAX headers are not supported" }

            val name = pendingPax["path"] ?: pendingLongName ?: headerName
            val paxSize = pendingPax["size"]
            val size = if (paxSize == null) {
                headerSize
            } else {
                requireNotNull(paxSize.toLongOrNull()) { "Archive entry has a malformed PAX size" }
            }
            require(size >= 0) { "Archive entry has an invalid size: $name" }
            val target = resolveEntry(root, name)

            when (type) {
                '\u0000', '0' -> {
                    ensureSafeParents(root, target.parent)
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output -> copyExactly(input, output, size) }
                    Os.chmod(target.toString(), mode and PERMISSION_MASK)
                }
                '5' -> {
                    ensureSafeParents(root, target.parent)
                    Files.createDirectories(target)
                    Os.chmod(target.toString(), mode and PERMISSION_MASK)
                }
                '1', '2' -> {
                    val linkName = pendingLongLink ?: pendingPax["linkpath"] ?: parseString(header, 157, 100)
                    deferredLinks += TarLink(target, linkName, hard = type == '1')
                    skipExactly(input, size)
                }
                else -> throw IllegalArgumentException("Unsupported tar entry type '$type' for $name")
            }

            skipExactly(input, paddingFor(size))
            pendingLongName = null
            pendingLongLink = null
            pendingPax.clear()
        }

        deferredLinks.forEach { link ->
            ensureSafeParents(root, link.path.parent)
            Files.createDirectories(link.path.parent)
            if (link.hard) {
                val hardTarget = resolveEntry(root, link.target)
                require(Files.isRegularFile(hardTarget, LinkOption.NOFOLLOW_LINKS)) {
                    "Hard-link target is not a regular archive file: ${link.target}"
                }
                Files.createLink(link.path, hardTarget)
            } else {
                require(link.target.isNotBlank()) { "Symbolic-link target is empty" }
                val archiveTarget = Path.of(link.target)
                val confinedTarget = if (archiveTarget.isAbsolute) {
                    // Rebase guest-absolute links so host filesystem traversal remains confined.
                    val guestTarget = root.resolve(link.target.removePrefix("/")).normalize()
                    require(guestTarget.startsWith(root)) { "Symbolic link escapes the root filesystem" }
                    link.path.parent.relativize(guestTarget)
                } else {
                    val guestTarget = link.path.parent.resolve(archiveTarget).normalize()
                    require(guestTarget.startsWith(root)) { "Symbolic link escapes the root filesystem" }
                    archiveTarget
                }
                Files.createSymbolicLink(link.path, confinedTarget)
            }
        }
    }

    private fun parseName(header: ByteArray): String {
        val name = parseString(header, 0, 100)
        val prefix = parseString(header, 345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun resolveEntry(root: Path, archiveName: String): Path {
        require(archiveName.isNotBlank()) { "Archive entry name is empty" }
        val relative = Path.of(archiveName).normalize()
        require(!relative.isAbsolute && !relative.startsWith("..")) {
            "Archive entry escapes the root filesystem: $archiveName"
        }
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) { "Archive entry escapes the staging directory" }
        return resolved
    }

    private fun ensureSafeParents(root: Path, parent: Path?) {
        if (parent == null) return
        var current = root
        root.relativize(parent).forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) {
                "Archive entry attempts to write through a symbolic link: $current"
            }
        }
    }

    private fun parseString(source: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { source[it] == 0.toByte() } ?: offset + length
        return String(source, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseOctal(source: ByteArray, offset: Int, length: Int): Long {
        val value = parseString(source, offset, length).trimStart('0', ' ')
        return if (value.isEmpty()) 0 else value.toLong(8)
    }

    private fun hasValidHeaderChecksum(header: ByteArray): Boolean {
        val expected = parseOctal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        val actual = header.indices.sumOf { index ->
            if (index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ASCII_SPACE
            } else {
                header[index].toInt() and 0xff
            }
        }.toLong()
        return actual == expected
    }

    private fun readMetadata(input: InputStream, size: Long): ByteArray {
        require(size in 0..MAX_METADATA_BYTES) { "Tar metadata record is too large" }
        return ByteArray(size.toInt()).also { metadata -> readFully(input, metadata, metadata.size) }
    }

    private fun parsePax(metadata: ByteArray): Map<String, String> {
        val values = mutableMapOf<String, String>()
        var offset = 0
        while (offset < metadata.size) {
            val space = (offset until metadata.size).firstOrNull { metadata[it] == ASCII_SPACE.toByte() }
            requireNotNull(space) { "Malformed PAX record length" }
            val recordLength = String(metadata, offset, space - offset, StandardCharsets.US_ASCII).toInt()
            val recordEnd = offset + recordLength
            require(recordLength > 0 && recordEnd <= metadata.size && metadata[recordEnd - 1] == '\n'.code.toByte()) {
                "Malformed PAX record"
            }
            val equals = (space + 1 until recordEnd - 1).firstOrNull { metadata[it] == '='.code.toByte() }
            requireNotNull(equals) { "Malformed PAX key/value record" }
            val key = String(metadata, space + 1, equals - space - 1, StandardCharsets.UTF_8)
            val value = String(metadata, equals + 1, recordEnd - equals - 2, StandardCharsets.UTF_8)
            if (key == "path" || key == "linkpath" || key == "size") values[key] = value
            offset = recordEnd
        }
        return values
    }

    private fun paddingFor(size: Long): Long = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            require(read >= 0) { "Unexpected end of tar archive" }
            offset += read
        }
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(read >= 0) { "Unexpected end of tar file payload" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "Unexpected end of tar padding" }
                remaining--
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private data class TarLink(val path: Path, val target: String, val hard: Boolean)

    private companion object {
        const val BLOCK_SIZE = 512
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PERMISSION_MASK = 0b111_111_111
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val ASCII_SPACE = 32
        const val MAX_METADATA_BYTES = 1024L * 1024L
    }
}
