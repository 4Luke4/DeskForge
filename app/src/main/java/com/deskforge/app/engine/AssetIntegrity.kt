package com.deskforge.app.engine

import java.io.File
import java.security.MessageDigest

/** Verifies a generated asset against the checksum committed beside it in the asset pack. */
object AssetIntegrity {
    private val checksumPattern = Regex("^[a-f0-9]{64}$")

    fun verifySha256(payload: File, checksumFile: File): String {
        require(payload.isFile) { "Asset payload is missing: ${payload.name}" }
        require(checksumFile.isFile && checksumFile.length() <= MAX_CHECKSUM_FILE_BYTES) {
            "Asset checksum is missing or malformed: ${checksumFile.name}"
        }

        val declaration = checksumFile.bufferedReader().use { it.readLine() }.orEmpty()
        val expected = declaration.substringBefore(' ').lowercase()
        require(checksumPattern.matches(expected)) { "Asset checksum is not a SHA-256 value" }

        val digest = MessageDigest.getInstance("SHA-256")
        payload.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        require(actual == expected) { "Asset checksum validation failed" }
        return actual
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_CHECKSUM_FILE_BYTES = 256L
}
