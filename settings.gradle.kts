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

rootProject.name = "private-email-agent"

include(":app")
include(":core:model")
include(":core:triage")
include(":core:mail")
include(":core:data")
include(":core:ai")
