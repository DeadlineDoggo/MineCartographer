pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "minecartographer"

// core: world formats and chunk operations, no UI dependencies (testable on its own)
// app:  Compose Desktop editor
include(":core", ":app")
