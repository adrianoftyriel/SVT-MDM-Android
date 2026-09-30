plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun signingProp(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? =
    signingProp("SVT_SIGNING_STORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
val hasReleaseKey: Boolean = releaseStoreFile != null &&
    signingProp("SVT_SIGNING_STORE_PASSWORD") != null &&
    signingProp("SVT_SIGNING_KEY_ALIAS") != null &&
    signingProp("SVT_SIGNING_KEY_PASSWORD") != null

android {
    namespace = "org.svt.mdm"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.svt.mdm"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.9.0"
    }

    signingConfigs {
        // v1 (JAR) signing is off by default for minSdk >= 24, but Android's
        // Device Owner provisioning verifier needs it to validate the
        // downloaded APK — without it, QR provisioning fails with
        // "Something went wrong". Keep v2/v3 as well.
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        // Release key comes from the environment / gradle properties only and
        // is never committed. Without it, release builds use the debug key.
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingProp("SVT_SIGNING_STORE_PASSWORD")
                keyAlias = signingProp("SVT_SIGNING_KEY_ALIAS")
                keyPassword = signingProp("SVT_SIGNING_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // Don't let lint fail the release build in CI (we still ship release for
    // the non-debuggable hardening; correctness is covered by the compile).
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // HiveMQ pulls in several Netty jars that each ship duplicate META-INF
    // metadata files; drop them so the APK packager can merge cleanly.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/io.netty.versions.properties",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.location)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hivemq.mqtt.client)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
