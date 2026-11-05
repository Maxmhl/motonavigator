import com.android.build.api.dsl.CommonExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// ---------------------------------------------------------------------------
// Gemeinsame Android-Konfiguration für alle Module
// ---------------------------------------------------------------------------
subprojects {
    val configureAndroid: CommonExtension<*, *, *, *, *, *>.() -> Unit = {
        compileSdk = 36
        defaultConfig {
            minSdk = 29
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }
        lint {
            abortOnError = true
            warningsAsErrors = false
            checkDependencies = true
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        }
    }
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension>("android") { configureAndroid() }
    }
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.gradle.internal.dsl.BaseAppModuleExtension>("android") { configureAndroid() }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

// ---------------------------------------------------------------------------
// Harte Regel 1: Netzwerkzugriff NUR in :data:download.
// Dieser Check scannt alle Quelltexte und Build-Dateien und bricht den Build ab,
// sobald außerhalb von data/download ein HTTP-Client oder Socket benutzt wird.
// Er hängt an jedem `check` (und damit an CI/Tests).
// ---------------------------------------------------------------------------
val verifyNoNetworkOutsideDownload by tasks.registering {
    group = "verification"
    description = "Prüft, dass nur :data:download Netzwerk-APIs benutzt (Spezifikation 1.1)."
    val root = layout.projectDirectory.asFile
    inputs.files(fileTree(root) {
        include("**/src/**/*.kt", "**/src/**/*.java", "**/build.gradle.kts")
        exclude("**/build/**", "data/download/**", "build.gradle.kts", "tools/**")
    })
    outputs.upToDateWhen { false }
    doLast {
        val forbiddenSource = listOf(
            Regex("""\bimport\s+okhttp3\."""),
            Regex("""\bimport\s+retrofit2\."""),
            Regex("""\bimport\s+io\.ktor\."""),
            Regex("""\bimport\s+java\.net\.(URL|HttpURLConnection|Socket|URLConnection|http\.)"""),
            Regex("""\bimport\s+javax\.net\.ssl\."""),
            Regex("""\bimport\s+android\.net\.http\."""),
            Regex("""\bimport\s+com\.android\.volley\."""),
            Regex("""\bHttpURLConnection\b"""),
            Regex("""\bopenConnection\s*\("""),
        )
        val forbiddenBuild = listOf(
            Regex("""libs\.okhttp\b"""),
            Regex("""com\.squareup\.okhttp3"""),
            Regex("""retrofit"""),
            Regex("""io\.ktor"""),
            Regex("""firebase|crashlytics|sentry|analytics""", RegexOption.IGNORE_CASE),
        )
        val violations = mutableListOf<String>()
        inputs.files.forEach { file ->
            val rel = file.relativeTo(root).path
            val rules = if (file.name == "build.gradle.kts") forbiddenBuild else forbiddenSource
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (rules.any { it.containsMatchIn(code) }) violations += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Netzwerkzugriff außerhalb von data/download gefunden (Spezifikation 1.1):\n" +
                    violations.joinToString("\n"),
            )
        }
        logger.lifecycle("OK: Netzwerk-APIs nur in :data:download.")
    }
}

subprojects {
    tasks.matching { it.name == "check" || it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyNoNetworkOutsideDownload"))
    }
}

// Robolectric lädt sein android-all-Image zur Testlaufzeit; optional über einen
// Spiegel (z. B. in ~/.gradle/gradle.properties: robolectricRepoUrl=…).
subprojects {
    tasks.withType<Test>().configureEach {
        providers.gradleProperty("robolectricRepoUrl").orNull?.let {
            systemProperty("robolectric.dependency.repo.url", it)
        }
        // Optional: echte BRouter-Segmente für den Routing-Integrationstest (-PtetSegmentsDir=…)
        providers.gradleProperty("tetSegmentsDir").orNull?.let { systemProperty("tet.segments", it) }
        // Optional: Live-Test gegen die öffentlichen Quellen (-PtetLiveOut=/ausgabeordner)
        providers.gradleProperty("tetLiveOut").orNull?.let { systemProperty("tet.live.out", it) }
        providers.gradleProperty("tetLiveSizes").orNull?.let { systemProperty("tet.live.sizes", it) }
        maxHeapSize = "2g"
    }
}
