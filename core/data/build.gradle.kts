plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // Triage settings (T42/T43) are stored as JSON in DataStore.
    alias(libs.plugins.kotlin.serialization)
}

room {
    // Exported schemas are committed; migrations are tested against them.
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "tech.elephantclock.privatemail.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas as assets.
        getByName("test").assets.srcDir("$projectDir/schemas")
        // The golden fixtures (T23) drive the orchestrator's end-to-end test.
        getByName("test").resources.srcDir(rootProject.file("core/triage/src/test/resources"))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // GreenMail's TLS server (sync tests) uses :core:mail's test certificate.
                val keystore = rootProject.file("core/mail/src/test/resources/tls/localhost.p12").absolutePath
                it.systemProperty("greenmail.tls.keystore.file", keystore)
                it.systemProperty("greenmail.tls.keystore.password", "changeit")
                it.systemProperty("greenmail.tls.key.password", "changeit")
                it.systemProperty("pea.test.keystore", keystore)
                // Robolectric's FileDescriptor interceptor reads JDK internals.
                // T25 real-mailbox evaluation; skipped unless PEA_LIVE_EVAL=1 (never on CI).
                System.getenv("PEA_LIVE_EVAL")?.let { value -> it.systemProperty("pea.liveEval", value) }
                it.systemProperty("pea.repoRoot", rootDir.absolutePath)
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }

    packaging {
        resources {
            // The test APK bundles Angus Mail; its jars each ship these.
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }

    lint {
        warningsAsErrors = true
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:mail"))
    // Triage orchestrator (T24): MailItem, Decision and the rules engine.
    api(project(":core:triage"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    api(libs.androidx.datastore)
    api(libs.paging.common)
    ksp(libs.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.greenmail)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
