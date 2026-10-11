plugins {
    id("com.android.application")
    // Note: AGP 9 has built-in Kotlin support, so 'org.jetbrains.kotlin.android' must NOT be
    // applied — doing so fails the build. The Compose compiler is still a separate plugin.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.rootexplorer.pro"

    // This machine's SDK contains exactly one platform, `platforms/android-37.0`, whose
    // source.properties declares AndroidVersion.ApiLevel=37.0. The directory name is what
    // AGP resolves against, so compileSdk must match it.
    compileSdk = 37

    // Pinned deliberately: this machine's SDK only has build-tools 36.0.0 installed, and the
    // SDK directory is not writable by the build, so AGP cannot auto-install the version it
    // would otherwise default to (35.0.0). Keep this in sync with `sdkmanager --list`.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.rootexplorer.pro"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-P3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
        aidl = false
        renderScript = false
        shaders = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Read-only archive formats the JDK cannot handle (tar and its compressed variants).
    // ZIP is implemented directly on java.util.zip instead, so this dependency is not on the
    // extraction path for the format users create most often.
    implementation("org.apache.commons:commons-compress:1.28.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Provides runTest, which is how the search flow is driven in unit tests without a device.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
