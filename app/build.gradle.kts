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
        }
        release {
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
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    assetPacks += listOf(":fedora_xfce_44")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
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
