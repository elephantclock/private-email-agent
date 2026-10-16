plugins {
    alias(libs.plugins.android.library)
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric's FileDescriptor interceptor reads JDK internals.
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
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
