plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tolik.mishar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tolik.mishar"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // A fixed key, so the app's fingerprint (SHA1) registered in the Spotify dashboard never changes.
    signingConfigs {
        create("tolik") {
            storeFile = file("tolik.keystore")
            storePassword = "tolikgame"
            keyAlias = "tolik"
            keyPassword = "tolikgame"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("tolik") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("tolik")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Spotify App Remote: talks directly to the Spotify app on the phone to play songs
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1")
}
