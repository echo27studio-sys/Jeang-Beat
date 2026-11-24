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

rootProject.name = "JeangBeat"

include(":app")
include(":core:model")
include(":core:data")
include(":core:audio")
include(":core:ui")
include(":feature:pattern")
include(":feature:pianoroll")
include(":feature:playlist")
include(":feature:mixer")
