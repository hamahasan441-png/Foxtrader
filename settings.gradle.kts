pluginManagement {
    repositories {
        google {
            content {
                // AGP pulls com.google.testing.platform (and KSP) from here.
                // Narrowing this to com.google.android.* dropped core-proto and
                // failed every CI run of #205.
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
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
