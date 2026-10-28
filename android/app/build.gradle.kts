plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tvplayer.signage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvplayer.signage"
        minSdk = 21
        targetSdk = 33
        versionCode = 6
        versionName = "2.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
