pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Shadey"

// The pure-Kotlin engine. Has NO Android dependencies and can be built and
// unit-tested with only a JDK + Maven Central (no Android SDK required).
include(":core")

// The Android application. Requires the Android SDK and Google's Maven repo.
// Set SHADEY_CORE_ONLY=true to configure only :core, e.g. to run the engine's
// unit tests in an environment without the Android toolchain.
if (System.getenv("SHADEY_CORE_ONLY") != "true") {
    include(":app")
}
