pluginManagement {
    repositories {
        // Portal first. KSP (com.google.devtools.ksp) is published here.
        // Listing every com.google.* group on dl.google.com made a single
        // maven blip fail plugin resolution before the portal could satisfy it
        // (PR #204, Actions run 44067027510).
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FoxTrader"
include(":app")
include(":benchmark")
