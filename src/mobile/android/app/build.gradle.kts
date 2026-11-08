plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
}

val restoreGigaAmArchive = tasks.register<RestoreGigaAmArchive>("restoreGigaAmArchive") {
    manifestFile.set(layout.projectDirectory.file("../model-parts/gigaam/manifest.json"))
    partsDirectory.set(layout.projectDirectory.dir("../model-parts/gigaam"))
    outputDirectory.set(layout.buildDirectory.dir("generated/gigaamAssets"))
}

android {
    namespace = "com.example.fluffydisco"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.fluffydisco"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += "7z"
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            restoreGigaAmArchive,
            RestoreGigaAmArchive::outputDirectory,
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigation.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.onnxruntime.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(this.platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}