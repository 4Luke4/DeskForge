plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val securedBuildDependencies = linkedMapOf<String, String>()
rootProject.file("config/build-tool-security.versions").readLines().forEachIndexed { index, line ->
    val entry = line.trim()
    if (entry.isEmpty() || entry.startsWith("#")) return@forEachIndexed

    val separator = entry.indexOf('=')
    require(separator > 0 && separator < entry.lastIndex) {
        "Invalid build-tool security entry at line ${index + 1}"
    }
    val coordinate = entry.substring(0, separator).trim()
    val version = entry.substring(separator + 1).trim()
    require(coordinate.count { it == ':' } == 1) {
        "Invalid build-tool coordinate at line ${index + 1}: $coordinate"
    }
    require(securedBuildDependencies.put(coordinate, version) == null) {
        "Duplicate build-tool security entry: $coordinate"
    }
}

val kotlinVersion = libs.versions.kotlin.get()
val kotlinBuildToolCoordinates = setOf(
    "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable",
    "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "org.jetbrains.kotlin:kotlin-gradle-plugin-annotations",
    "org.jetbrains.kotlin:kotlin-gradle-plugin-api",
    "org.jetbrains.kotlin:kotlin-gradle-plugin-idea",
    "org.jetbrains.kotlin:kotlin-gradle-plugin-idea-proto",
    "org.jetbrains.kotlin:kotlin-gradle-plugins-bom",
)
val auditedBuildDependencies = securedBuildDependencies +
    kotlinBuildToolCoordinates.associateWith { kotlinVersion }

tasks.register("verifySecureBuildDependencies") {
    group = "verification"
    description = "Verifies patched versions across resolved build and project dependency graphs."
    notCompatibleWithConfigurationCache("The task intentionally audits every resolvable configuration")

    doLast {
        val observedVersions = auditedBuildDependencies.keys
            .associateWith { mutableSetOf<String>() }

        rootProject.allprojects.forEach { project ->
            val configurations =
                project.buildscript.configurations.filter { it.isCanBeResolved } +
                    project.configurations.filter { it.isCanBeResolved }

            configurations.forEach { configuration ->
                configuration.incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .forEach { module ->
                        val coordinate = "${module.group}:${module.name}"
                        observedVersions[coordinate]?.add(module.version)
                    }
            }
        }

        val failures = auditedBuildDependencies.mapNotNull { (coordinate, expectedVersion) ->
            val actualVersions = observedVersions.getValue(coordinate)
            when {
                actualVersions.isEmpty() -> "$coordinate was not present in the resolved graph"
                actualVersions != setOf(expectedVersion) ->
                    "$coordinate resolved to ${actualVersions.sorted()} instead of $expectedVersion"
                else -> null
            }
        }
        check(failures.isEmpty()) {
            "Build dependency security verification failed:\n${failures.joinToString("\n")}"
        }

        auditedBuildDependencies.toSortedMap().forEach { (coordinate, version) ->
            logger.lifecycle("Verified secured build dependency: {}:{}", coordinate, version)
        }
    }
}
