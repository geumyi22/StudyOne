plugins {
    id("com.android.application")
}

android {
    namespace = "com.studyone.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.studyone.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 20000
        versionName = "2.0.0-dev.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
