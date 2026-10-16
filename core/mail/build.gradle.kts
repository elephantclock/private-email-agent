plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.elephantclock.privatemail.core.mail"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // GreenMail's TLS servers read their certificate once per JVM from these properties.
            val keystore = file("src/test/resources/tls/localhost.p12").absolutePath
            it.systemProperty("greenmail.tls.keystore.file", keystore)
            it.systemProperty("greenmail.tls.keystore.password", "changeit")
            it.systemProperty("greenmail.tls.key.password", "changeit")
            // Real-server check (T11 [USER]); skipped unless set.
            System.getenv("PEA_LIVE_CHECK")?.let { value -> it.systemProperty("pea.liveCheck", value) }
        }
    }

    lint {
        warningsAsErrors = true
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.angus.mail)

    testImplementation(libs.junit4)
    testImplementation(libs.greenmail)
    testImplementation(libs.mockwebserver)
}
