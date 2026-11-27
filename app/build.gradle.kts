plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.scooblydoon.thunbudget"
    compileSdk = 35
    // Forecast Engine v2: category profiles, Croston, damped trend, ranges and rolling-origin backtest.
    // Release artifact: ThunBudget Analytics 1.3.41 (versionCode 54), signed with the established release key.
// Includes list/calendar views, editable payment details and month-aware confirmation. Blended forecast, evidence-quality labels, outlier protection, recurring commitments, gray dialogs, tab background and cancellation-safe CI; final signed release. [final-apk]

    defaultConfig {
        applicationId = "ru.scooblydoon.thunbudget.analytics"
        minSdk = 26
        targetSdk = 35
        versionCode = 54
        versionName = "1.3.41"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("THUNBUDGET_KEYSTORE_FILE")
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("THUNBUDGET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("THUNBUDGET_KEY_ALIAS")
                keyPassword = System.getenv("THUNBUDGET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { animationsDisabled = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
