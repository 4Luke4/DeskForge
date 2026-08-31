pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val securedBuildDependencies = linkedMapOf<String, String>()
rootDir.resolve("config/build-tool-security.versions").readLines().forEachIndexed { index, line ->
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
require(securedBuildDependencies.isNotEmpty()) { "Build-tool security manifest must not be empty" }

val codeqlKotlinCompatibility = providers.gradleProperty("deskforge.codeqlKotlinCompatibility")
    .map { value -> value.toBooleanStrict() }
    .getOrElse(false)
val kotlinGradlePluginCoordinate = "org.jetbrains.kotlin:kotlin-gradle-plugin"

// AGP exposes vulnerable tooling through both plugin classpaths and ordinary internal configurations.
// Register before each project is evaluated so later-created configurations inherit the same pins.
gradle.beforeProject {
    if (codeqlKotlinCompatibility) {
        // CodeQL 2.26.4 cannot instrument Kotlin 2.4.20. Keep its isolated analysis build outside
        // the vulnerable KAPT cache path, and fail closed if annotation processing is introduced.
        setOf("org.jetbrains.kotlin.kapt", "kotlin-kapt").forEach { kaptPluginId ->
            pluginManager.withPlugin(kaptPluginId) {
                error("KAPT is forbidden in CodeQL Kotlin compatibility mode")
            }
        }
    }

    buildscript.configurations.configureEach {
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            securedBuildDependencies[coordinate]?.let { approvedVersion ->
                if (!codeqlKotlinCompatibility || coordinate != kotlinGradlePluginCoordinate) {
                    useVersion(approvedVersion)
                    because("DeskForge pins the audited patched build-tool dependency graph")
                }
            }
        }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val coordinate = "${requested.group}:${requested.name}"
            securedBuildDependencies[coordinate]?.let { approvedVersion ->
                if (!codeqlKotlinCompatibility || coordinate != kotlinGradlePluginCoordinate) {
                    useVersion(approvedVersion)
                    because("DeskForge pins the audited patched build-tool dependency graph")
                }
            }
        }
    }
}

rootProject.name = "DeskForge"
include(":app")
include(":fedora_xfce_44")
