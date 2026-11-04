plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.tetnavigator"

    defaultConfig {
        applicationId = "de.tetnavigator.app"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    // Release-Signatur: eigener Keystore über Umgebungsvariablen (lokal oder als
    // GitHub-Secrets), sonst Debug-Schlüssel (Eigengebrauch, sofort installierbar).
    val keystore = System.getenv("TET_KEYSTORE_FILE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("TET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TET_KEY_ALIAS")
                keyPassword = System.getenv("TET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    androidResources {
        // Glyphen und PMTiles dürfen nicht komprimiert werden (MapLibre liest direkt).
        noCompress += setOf("pbf", "pmtiles")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:preferences"))
    implementation(project(":core:offlinedata"))
    implementation(project(":data:download"))
    implementation(project(":feature:import"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:logistik"))
    implementation(project(":feature:logbuch"))
    implementation(project(":feature:bedienung"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Offline-Vektorkarten (PMTiles, lokaler Stil, keine Online-Tiles).
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
