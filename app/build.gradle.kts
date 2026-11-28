plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "xyz.needsme.android"
    compileSdk = 34
    defaultConfig {
        applicationId = "xyz.needsme.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 240
        versionName = "2.4.0"
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("NM_KEYSTORE") ?: "release.jks")
            storePassword = System.getenv("NM_KS_PASS") ?: "needsme-release"
            keyAlias = "needsme"
            keyPassword = System.getenv("NM_KS_PASS") ?: "needsme-release"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
