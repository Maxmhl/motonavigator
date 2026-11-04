plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "de.tetnavigator.feature.navigation"
}

dependencies {
    api(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:offlinedata"))
    api(project(":core:preferences"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Offline-Routing (BRouter-Segmente .rd5) – arbeitet nur auf lokalen Dateien.
    implementation(libs.brouter.core)
    implementation(libs.brouter.mapaccess)
    implementation(libs.brouter.util)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
