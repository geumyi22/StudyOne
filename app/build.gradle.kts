plugins {
    id("com.android.application")
}

val studyOneKeyFile = System.getenv("STUDYONE_KEYSTORE_FILE")

android {
    namespace = "com.studyone.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.studyone.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 30000
        versionName = "3.0.0-beta.1"
    }

    if (!studyOneKeyFile.isNullOrBlank()) {
        signingConfigs {
            create("studyoneProduction") {
                storeFile = file(studyOneKeyFile)
                storePassword = System.getenv("STUDYONE_STORE_PASSWORD")
                keyAlias = System.getenv("STUDYONE_KEY_ALIAS")
                keyPassword = System.getenv("STUDYONE_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!studyOneKeyFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("studyoneProduction")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
