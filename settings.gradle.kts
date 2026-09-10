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
        // GeckoView is published here and nowhere else. Mozilla does not mirror
        // it to Maven Central.
        maven("https://maven.mozilla.org/maven2")
    }
}

rootProject.name = "Aquarius"
include(":app")
