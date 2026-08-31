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
val kotlinBuildToolCoordinates = setOf("org.jetbrains.kotlin:kotlin-gradle-plugin")
val auditedBuildDependencies = securedBuildDependencies +
    kotlinBuildToolCoordinates.associateWith { kotlinVersion }

val secureBuildDependencyVerificationTasks = allprojects.map { auditedProject ->
    auditedProject.tasks.register("verifySecureBuildDependenciesForProject") {
        group = "verification"
        description = "Verifies patched versions in this project's resolved dependency graphs."
        notCompatibleWithConfigurationCache(
            "The task intentionally audits every resolvable project configuration",
        )

        doLast {
            val observedVersions = auditedBuildDependencies.keys
                .associateWith { mutableSetOf<String>() }

            // Resolve configurations only from their owning project's task to retain Gradle's project lock.
            val configurations =
                auditedProject.buildscript.configurations.filter { it.isCanBeResolved } +
                    auditedProject.configurations.filter { it.isCanBeResolved }

            configurations.forEach { configuration ->
                configuration.incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .forEach { module ->
                        val coordinate = "${module.group}:${module.name}"
                        observedVersions[coordinate]?.add(module.version)
                    }
            }

            val failures = auditedBuildDependencies.mapNotNull { (coordinate, expectedVersion) ->
                val actualVersions = observedVersions.getValue(coordinate)
                when {
                    actualVersions.isEmpty() && auditedProject == rootProject ->
                        "$coordinate was not present in the root build-tool graph"
                    actualVersions.isEmpty() -> null
                    actualVersions != setOf(expectedVersion) ->
                        "$coordinate resolved to ${actualVersions.sorted()} instead of $expectedVersion"
                    else -> null
                }
            }
            check(failures.isEmpty()) {
                "Build dependency security verification failed for ${auditedProject.path}:\n" +
                    failures.joinToString("\n")
            }

            observedVersions.filterValues { it.isNotEmpty() }.toSortedMap()
                .forEach { (coordinate, versions) ->
                    logger.lifecycle(
                        "Verified secured build dependency for {}: {}:{}",
                        auditedProject.path,
                        coordinate,
                        versions.single(),
                    )
                }
        }
    }
}

tasks.register("verifySecureBuildDependencies") {
    group = "verification"
    description = "Verifies patched versions across every project's resolved dependency graphs."
    dependsOn(secureBuildDependencyVerificationTasks)
}
