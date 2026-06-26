pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The `libs` version catalog is auto-loaded from gradle/libs.versions.toml.
}

rootProject.name = ("potassium")

include(":plugin", ":updater")
