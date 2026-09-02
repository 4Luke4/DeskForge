package com.deskforge.app.engine

import org.json.JSONObject

internal data class FedoraPayloadManifest(
    val distroId: String,
    val release: String,
    val desktopHostVersion: String,
    val workspaceIntegrationVersion: Int,
    val audioHostPackages: List<String>,
    val graphicsHostPackages: List<String>,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val uncompressedSizeBytes: Long,
    val parts: List<Part>,
) {
    data class Part(
        val packName: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    companion object {
        private val digestPattern = Regex("^[a-f0-9]{64}$")
        private val packPattern = Regex("^fedora_xfce_44(?:_[1-3])?$")
        private val partPattern = Regex("^rootfs\\.part[0-3][0-9]$")
        private val packageIdentityPattern = Regex("^[A-Za-z0-9+_.:-]{1,160}$")

        fun parse(json: String): FedoraPayloadManifest {
            require(json.length <= MAX_MANIFEST_CHARACTERS) { "Fedora payload manifest is too large" }
            val root = JSONObject(json)
            require(root.getInt("schemaVersion") == 4) { "Unsupported Fedora payload manifest" }
            val workspaceIntegrationVersion = root.getInt("workspaceIntegrationVersion")
            require(workspaceIntegrationVersion > 0) { "Invalid Fedora workspace integration version" }
            val audioPackagesJson = root.getJSONArray("audioHostPackages")
            require(audioPackagesJson.length() in 1..16) { "Invalid Fedora audio package inventory" }
            val audioHostPackages = buildList(audioPackagesJson.length()) {
                repeat(audioPackagesJson.length()) { index ->
                    add(audioPackagesJson.getString(index).also { identity ->
                        require(packageIdentityPattern.matches(identity)) {
                            "Invalid Fedora audio package identity"
                        }
                    })
                }
            }
            val graphicsPackagesJson = root.getJSONArray("graphicsHostPackages")
            require(graphicsPackagesJson.length() in 1..16) { "Invalid Fedora graphics package inventory" }
            val graphicsHostPackages = buildList(graphicsPackagesJson.length()) {
                repeat(graphicsPackagesJson.length()) { index ->
                    add(graphicsPackagesJson.getString(index).also { identity ->
                        require(packageIdentityPattern.matches(identity)) {
                            "Invalid Fedora graphics package identity"
                        }
                    })
                }
            }
            val partsJson = root.getJSONArray("parts")
            require(partsJson.length() in 1..4) { "Fedora payload has an invalid part count" }
            val parts = buildList(partsJson.length()) {
                repeat(partsJson.length()) { index ->
                    val part = partsJson.getJSONObject(index)
                    val packName = part.getString("packName")
                    val fileName = part.getString("fileName")
                    val sizeBytes = part.getLong("sizeBytes")
                    val sha256 = part.getString("sha256")
                    require(packPattern.matches(packName)) { "Fedora payload contains an invalid pack name" }
                    val expectedPack = if (index == 0) "fedora_xfce_44" else "fedora_xfce_44_$index"
                    require(packName == expectedPack) { "Fedora payload packs are out of order" }
                    require(partPattern.matches(fileName)) { "Fedora payload contains an invalid part name" }
                    require(fileName == "rootfs.part${index.toString().padStart(2, '0')}") {
                        "Fedora payload parts are out of order"
                    }
                    require(sizeBytes in 1..MAX_PART_BYTES) { "Fedora payload part size is invalid" }
                    require(digestPattern.matches(sha256)) { "Fedora payload part checksum is invalid" }
                    add(Part(packName, fileName, sizeBytes, sha256))
                }
            }
            val archiveSha256 = root.getString("archiveSha256")
            val archiveSizeBytes = root.getLong("archiveSizeBytes")
            val uncompressedSizeBytes = root.getLong("uncompressedSizeBytes")
            require(digestPattern.matches(archiveSha256)) { "Fedora archive checksum is invalid" }
            require(archiveSizeBytes in 1..MAX_ARCHIVE_BYTES) { "Fedora archive size is invalid" }
            require(archiveSizeBytes == parts.sumOf(Part::sizeBytes)) { "Fedora archive size is inconsistent" }
            require(uncompressedSizeBytes in (archiveSizeBytes + 1)..MAX_UNCOMPRESSED_BYTES) {
                "Fedora unpacked size is invalid"
            }
            return FedoraPayloadManifest(
                distroId = root.getString("distroId").also { require(it == "fedora-xfce-44") },
                release = root.getString("release").also { require(it == "44") },
                desktopHostVersion = root.getString("desktopHostVersion").also { require(it.isNotBlank()) },
                workspaceIntegrationVersion = workspaceIntegrationVersion,
                audioHostPackages = audioHostPackages,
                graphicsHostPackages = graphicsHostPackages,
                archiveSha256 = archiveSha256,
                archiveSizeBytes = archiveSizeBytes,
                uncompressedSizeBytes = uncompressedSizeBytes,
                parts = parts,
            )
        }

        private const val MAX_MANIFEST_CHARACTERS = 32 * 1024
        private const val MAX_PART_BYTES = 1_400_000_000L
        private const val MAX_ARCHIVE_BYTES = 4_000_000_000L
        private const val MAX_UNCOMPRESSED_BYTES = 24L * 1024L * 1024L * 1024L
    }
}
