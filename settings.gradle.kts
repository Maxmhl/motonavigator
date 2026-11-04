pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Nur für BRouter (Offline-Routing, GPL/MIT – siehe licenses/).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.abrensch.brouter") }
        }
    }
}

rootProject.name = "TET-Navigator"

include(
    ":app",
    ":core:common",
    ":core:designsystem",
    ":core:database",
    ":core:preferences",
    ":core:offlinedata",
    ":data:download",
    ":feature:import",
    ":feature:navigation",
    ":feature:logistik",
    ":feature:logbuch",
    ":feature:bedienung",
    ":feature:setup",
    ":feature:settings",
)
