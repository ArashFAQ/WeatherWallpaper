plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "ir.daftarak.weatherwall"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.daftarak.weatherwall"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
    }

    signingConfigs {
        create("wall") {
            storeFile = file("wall.keystore")
            storePassword = "android"
            keyAlias = "wall"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("wall")
        }
        debug {
            signingConfig = signingConfigs.getByName("wall")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    androidResources {
        noCompress += "webp"
    }
}
