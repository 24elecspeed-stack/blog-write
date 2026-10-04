plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.keyword.research"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keyword.research"
        minSdk = 29
        targetSdk = 35
        versionCode = 222
        versionName = "3.20"
        buildConfigField("String", "PASTE_SERVICE_CLASS", "\"com.keyword.research.NaverPasteAccessibilityService\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        create("headingTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".headingtest"
            versionNameSuffix = "-TEST-01"
            buildConfigField("String", "PASTE_SERVICE_CLASS", "\"com.keyword.research.HeadingTestService\"")
            matchingFallbacks += listOf("debug")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
