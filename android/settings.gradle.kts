pluginManagement {
    repositories {
        google()
        // CI runners can encounter HTTP 429 from Maven Central; prefer its GCS mirror.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // CI runners can encounter HTTP 429 from Maven Central; prefer its GCS mirror.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "OpenRayneoAndroid"
include(":app")
