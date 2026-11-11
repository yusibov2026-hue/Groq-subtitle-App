plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ejemplo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ejemplo.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// Sin dependencias a propósito: solo el marco de Android, así el primer APK compila
// sin bajar bibliotecas. Cuando quieras AndroidX o Compose, se añaden aquí.
dependencies {
}
