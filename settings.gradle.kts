pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZeroV2"

// Phase 1: Core infrastructure modules
include(":core:common")
include(":core:bus")
include(":core:module-api")
include(":core:module-manager")
include(":core:trace")

// Phase 2: Service and Framework modules
include(":services")
include(":framework:chat")

include(":framework:memory")
include(":core:data")

include(":app")
include(":benchmark")

// Future phases
// include(":core:di")
// include(":framework:module")
