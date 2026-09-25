// Used only when Gradle is started inside phone/, which is what F-Droid does
// with subdir: phone. The repository-root settings file is used otherwise.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.0.1"
        kotlin("jvm") version "2.2.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glucowatch-phone"
include(":core")
project(":core").projectDir = file("../core")
