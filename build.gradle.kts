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

val secureBuildDependencyReports = mutableListOf<Provider<RegularFile>>()
val secureBuildDependencyVerificationTasks = allprojects.map { auditedProject ->
    val verificationReport =
        auditedProject.layout.buildDirectory.file("reports/security/secure-build-dependencies.txt")
    secureBuildDependencyReports += verificationReport

    auditedProject.tasks.register("verifySecureBuildDependenciesForProject") {
        group = "verification"
        description = "Verifies patched versions in this project's resolved dependency graphs."
        outputs.file(verificationReport)
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

            val selectedVersions = observedVersions.filterValues { it.isNotEmpty() }
                .mapValues { (_, versions) -> versions.single() }
                .toSortedMap()
            val reportFile = verificationReport.get().asFile
            reportFile.parentFile.mkdirs()
            reportFile.writeText(
                selectedVersions.entries.joinToString(separator = "\n", postfix = "\n") {
                    (coordinate, version) -> "$coordinate=$version"
                },
            )
        }
    }
}

tasks.register("verifySecureBuildDependencies") {
    group = "verification"
    description = "Verifies patched versions across every project's resolved dependency graphs."
    dependsOn(secureBuildDependencyVerificationTasks)
    inputs.files(secureBuildDependencyReports)
    notCompatibleWithConfigurationCache(
        "The task aggregates reports from every project's dependency audit",
    )

    doLast {
        val observedVersions = auditedBuildDependencies.keys
            .associateWith { mutableSetOf<String>() }

        // Aggregate file reports instead of resolving another project's configurations unsafely.
        secureBuildDependencyReports.forEach { report ->
            report.get().asFile.readLines().filter { it.isNotBlank() }.forEach { entry ->
                val separator = entry.lastIndexOf('=')
                check(separator > 0 && separator < entry.lastIndex) {
                    "Invalid secured dependency report entry: $entry"
                }
                val coordinate = entry.substring(0, separator)
                val version = entry.substring(separator + 1)
                observedVersions.getValue(coordinate).add(version)
            }
        }

        val failures = auditedBuildDependencies.mapNotNull { (coordinate, expectedVersion) ->
            val actualVersions = observedVersions.getValue(coordinate)
            when {
                actualVersions.isEmpty() -> "$coordinate was not present in any resolved graph"
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
