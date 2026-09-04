import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val semanticVersion = rootProject.file("VERSION").readText().trim().removePrefix("v")
val versionParts = semanticVersion.substringBefore('-').split('.').map(String::toInt)
require(versionParts.size == 3) { "VERSION must contain a SemVer core in MAJOR.MINOR.PATCH form" }
require(versionParts[1] <= 999 && versionParts[2] <= 999) {
    "VERSION minor and patch components must fit the collision-free Android version-code mapping"
}
val generatedVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
require(generatedVersionCode in 1..2_100_000_000) { "VERSION exceeds Google Play's version-code range" }

val prootManifest = JsonSlurper().parse(rootProject.file("config/proot/version.json")) as Map<*, *>
require(prootManifest["schemaVersion"] == 2) { "Unsupported PRoot manifest schema" }
val prootMetadata = prootManifest["proot"] as Map<*, *>
val prootBinary = prootManifest["binary"] as Map<*, *>
val prootVersion = prootMetadata["version"] as String
val prootRuntime = prootBinary["runtime"] as Map<*, *>
val prootLoader = prootBinary["loader"] as Map<*, *>
val prootSha256 = prootRuntime["sha256"] as String
val prootSizeBytes = (prootRuntime["sizeBytes"] as Number).toLong()
val prootLoaderSha256 = prootLoader["sha256"] as String
val prootLoaderSizeBytes = (prootLoader["sizeBytes"] as Number).toLong()
require(Regex("^[a-f0-9]{64}$").matches(prootSha256)) { "Invalid PRoot binary SHA-256" }
require(Regex("^[a-f0-9]{64}$").matches(prootLoaderSha256)) { "Invalid PRoot loader SHA-256" }
require(prootRuntime["fileName"] == "libproot.so") { "Unexpected PRoot runtime file name" }
require(prootLoader["fileName"] == "libproot-loader.so") { "Unexpected PRoot loader file name" }

val fedoraManifest = JsonSlurper().parse(rootProject.file("config/distros/fedora-xfce-44.json")) as Map<*, *>
require(fedoraManifest["schemaVersion"] == 5) { "Unsupported Fedora distribution manifest schema" }
val fedoraWorkspaceIntegrationVersion =
    (fedoraManifest["workspaceIntegrationVersion"] as Number).toInt()
require(fedoraWorkspaceIntegrationVersion > 0) { "Invalid Fedora workspace integration version" }

val graphicsManifest = JsonSlurper().parse(rootProject.file("config/graphics/runtime.json")) as Map<*, *>
require(graphicsManifest["schemaVersion"] == 2) { "Unsupported graphics manifest schema" }
val graphicsBinary = graphicsManifest["binary"] as Map<*, *>
val graphicsTransport = graphicsManifest["transport"] as Map<*, *>
require(graphicsBinary["fileName"] == "libdeskforge_graphics.so") { "Unexpected graphics runtime file name" }
require(graphicsBinary["renderServerFileName"] == "libdeskforge_venus_server.so") {
    "Unexpected Venus render-server file name"
}
val graphicsSocketName = graphicsTransport["socketName"] as String
require(graphicsSocketName.matches(Regex("[a-z0-9._-]+"))) { "Invalid graphics socket name" }
val graphicsStartupTimeoutMs = (graphicsTransport["startupTimeoutMs"] as Number).toLong()
require(graphicsStartupTimeoutMs in 1_000..30_000) { "Invalid graphics startup timeout" }

val androidToolchain = Properties().apply {
    rootProject.file("config/android/toolchain.properties").inputStream().use(::load)
}
fun toolchainInt(name: String): Int = requireNotNull(androidToolchain.getProperty(name)) {
    "Missing Android toolchain property: $name"
}.toInt()
fun toolchainString(name: String): String = requireNotNull(androidToolchain.getProperty(name)) {
    "Missing Android toolchain property: $name"
}

android {
    namespace = "com.deskforge.app"
    compileSdk {
        version = release(toolchainInt("compileSdk")) {
            // Android 17's current finalized SDK is published as a minor API platform.
            minorApiLevel = toolchainInt("compileSdkMinor")
        }
    }
    buildToolsVersion = toolchainString("buildToolsVersion")
    ndkVersion = toolchainString("ndkVersion")

    defaultConfig {
        applicationId = "com.deskforge.app"
        minSdk = toolchainInt("minSdk")
        targetSdk = toolchainInt("targetSdk")
        versionCode = generatedVersionCode
        versionName = semanticVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["minimumTabletWidthDp"] = toolchainString("minimumTabletWidthDp")
        buildConfigField("String", "PROOT_VERSION", "\"$prootVersion\"")
        buildConfigField("String", "PROOT_SHA256", "\"$prootSha256\"")
        buildConfigField("long", "PROOT_SIZE_BYTES", "${prootSizeBytes}L")
        buildConfigField("String", "PROOT_LOADER_SHA256", "\"$prootLoaderSha256\"")
        buildConfigField("long", "PROOT_LOADER_SIZE_BYTES", "${prootLoaderSizeBytes}L")
        buildConfigField(
            "int",
            "FEDORA_WORKSPACE_INTEGRATION_VERSION",
            fedoraWorkspaceIntegrationVersion.toString(),
        )
        buildConfigField("String", "GRAPHICS_SOCKET_NAME", "\"${graphicsSocketName}\"")
        buildConfigField("long", "GRAPHICS_STARTUP_TIMEOUT_MS", "${graphicsStartupTimeoutMs}L")

        require(prootBinary["abi"] == toolchainString("abi")) {
            "PRoot binary ABI does not match the Android toolchain"
        }
        require((prootBinary["minimumApi"] as Number).toInt() == toolchainInt("minSdk")) {
            "PRoot binary API does not match minSdk"
        }
        require(graphicsBinary["abi"] == toolchainString("abi")) {
            "Graphics runtime ABI does not match the Android toolchain"
        }
        require((graphicsBinary["minimumApi"] as Number).toInt() == toolchainInt("minSdk")) {
            "Graphics runtime API does not match minSdk"
        }

        ndk {
            // DeskForge deliberately supports 64-bit ARM tablets only.
            abiFilters += toolchainString("abi")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk {
                // x86_64 exists only in debug artifacts so PR instrumentation can use Linux KVM.
                abiFilters += setOf(toolchainString("abi"), "x86_64")
            }
            buildConfigField("boolean", "EXPERIMENTAL_DIRECT_DISPLAY", "true")
        }
        release {
            buildConfigField("boolean", "EXPERIMENTAL_DIRECT_DISPLAY", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            val signingPropertiesFile = rootProject.file("signing.properties")
            if (signingPropertiesFile.isFile) {
                val signingProperties = Properties().apply {
                    signingPropertiesFile.inputStream().use(::load)
                }
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                    storePassword = signingProperties.getProperty("storePassword")
                    keyAlias = signingProperties.getProperty("keyAlias")
                    keyPassword = signingProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        val javaVersion = JavaVersion.toVersion(toolchainString("javaVersion"))
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = toolchainString("cmakeVersion")
        }
    }

    packaging {
        // The separately executed PRoot binary must be extracted into the executable native-lib directory.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    assetPacks += listOf(
        ":fedora_xfce_44",
        ":fedora_xfce_44_1",
        ":fedora_xfce_44_2",
        ":fedora_xfce_44_3",
    )

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // ActivityResult permission handling requires a non-vulnerable Fragment runtime on the graph.
    implementation(libs.androidx.fragment)
    implementation(libs.play.asset.delivery)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
