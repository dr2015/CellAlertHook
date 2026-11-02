import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The signing key and its password stay out of the project. Without this file, a release is unsigned.
val signing = providers.environmentVariable("CELLALERTHOOK_SIGNING_FILE").orNull
    ?.let(::file)
    ?.let { source -> Properties().apply { source.inputStream().use(::load) } }

android {
    namespace = "app.cellalerthook"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cellalerthook"
        // Android 11 is where the Cell Broadcast stack and its history provider
        // moved into the Mainline module this app reads. See docs/COMPATIBILITY.md.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Sealed links for a preset build, from tools/build-preset-apk.js. Every other build carries none.
    sourceSets {
        providers.environmentVariable("CELLALERTHOOK_PRESET_DIR").orNull?.let { getByName("release").assets.srcDir(it) }
    }

    signingConfigs {
        signing?.let {
            create("release") {
                storeFile = file(it.getProperty("storeFile"))
                storePassword = it.getProperty("storePassword")
                keyAlias = it.getProperty("keyAlias")
                keyPassword = it.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        // The READ_CELL_BROADCASTS grant is checked at runtime by CellBroadcastCapabilityChecker.
        disable += "MissingPermission"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
