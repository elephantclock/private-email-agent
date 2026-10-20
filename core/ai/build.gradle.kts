plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.elephantclock.privatemail.core.ai"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
    }
}

dependencies {
    // Verdict, FolderTarget, MessageHeader and StateFlow are part of this module's API.
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    // Gemini Nano through AICore (beta: no stable release yet, see T01 Log).
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.common)
    implementation(libs.mlkit.genai.summarization)
    // T33: rewriting and proofreading (Gemini Nano), language ID and translation (own small models).
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
