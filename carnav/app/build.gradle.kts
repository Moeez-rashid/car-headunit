plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moeez.carnav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moeez.carnav"
        minSdk = 24
        targetSdk = 29 // the head unit runs Android 10 (API 29)
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        // Release builds are signed with the SAME key as the debug builds and
        // CarDash (this Mac's ~/.android/debug.keystore). That keeps:
        //  - updates installing over earlier CarNav builds without an uninstall
        //    (which would wipe recents and cached road names), and
        //  - the signature-level CarDash permission (mini player) working.
        // Moving to a private release key means re-signing CarDash with it too.
        create("sameAsCarDash") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // no R8: keeps osmdroid/Compose behaviour identical to tested debug builds
            isDebuggable = false
            signingConfig = signingConfigs.getByName("sameAsCarDash")
        }
    }

    lint {
        // Play Store rule only. CarNav is sideloaded onto an Android 10 head
        // unit and deliberately targets API 29; nothing else is suppressed.
        disable += "ExpiredTargetSdkVersion"
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2") // state survives recreation (L3)
    // Map view drawn with plain Canvas — no OpenGL ES 3 needed (the unit only has ES 2).
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
