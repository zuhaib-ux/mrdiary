plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zuhaib.mrdiary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zuhaib.mrdiary"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "3.0"
    }

    // Har build ek hi key se sign ho, taake naya version purane par install ho aur data na mite
    signingConfigs {
        getByName("debug") {
            storeFile = file("mrdiary.jks")
            storePassword = "mrdiary123"
            keyAlias = "mrdiary"
            keyPassword = "mrdiary123"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
