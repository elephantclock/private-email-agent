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
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
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
