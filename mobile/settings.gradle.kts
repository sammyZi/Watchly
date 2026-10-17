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
    }
}
rootProject.name = "Watchly"
include(":app", ":core")
// Engine, players, indexers, store and the look, shared with the TV app.
project(":core").projectDir = file("../tv/core")
