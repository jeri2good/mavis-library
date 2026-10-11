pluginManagement {
    repositories {
        // Local Maven is checked first so pre-staged artifacts work with the required --offline lane.
        mavenLocal()
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
    }
}

rootProject.name = "MavisLibrary"
include(":app")
