plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tiktokmusic.clean"
    compileSdk = 34

    defaultConfig {
        // Sử dụng applicationId "com.luna.music" (TikTok/Douyin Music chính thức) nằm trong Whitelist mặc định của Đảo Origin (Origin Island) trên Vivo OriginOS 5 & OriginOS 6
        applicationId = "com.luna.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0-OriginOS6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
