pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central: avoids 429 rate limits from repo1 in shared sandboxes/CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google-hosted mirror of Maven Central: avoids 429 rate limits from repo1 in shared sandboxes/CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "Heartline"

include(":shared", ":datalayer", ":phone", ":wear")
