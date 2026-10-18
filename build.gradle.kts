plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless)
}

spotless {
    // fileTree with excludes prunes build folders from the walk itself; target + targetExclude
    // still walked them and raced with lint writing there ("Could not read path .../build/...").
    kotlin {
        target(fileTree(rootDir) { include("**/*.kt").exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**") })
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            fileTree(rootDir) { include("**/*.gradle.kts").exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**") },
        )
        ktlint(libs.versions.ktlint.get())
    }
}

// JVM-only modules have `test`, Android modules have `testDebugUnitTest`; this runs both.
tasks.register("unitTest") {
    group = "verification"
    description = "Runs unit tests in every module."
    dependsOn(
        ":core:model:test",
        ":core:triage:test",
        ":core:mail:testDebugUnitTest",
        ":core:data:testDebugUnitTest",
        ":core:ai:testDebugUnitTest",
        ":app:testDebugUnitTest",
    )
}
