plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "red.sismo"
    compileSdk = 34

    defaultConfig {
        applicationId = "red.sismo"
        minSdk = 26                 // Android 8: servicios en primer plano
        targetSdk = 34              // 34 exige declarar el tipo de servicio
        versionCode = 1
        versionName = "0.1-fase1"
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
    buildFeatures {
        viewBinding = true
        // Para que «Acerca de» saque la versión de aquí y no de un literal.
        buildConfig = true
    }
}

// Mínimas a propósito: nada de SDK de terceros, nada de pago, nada que ate el
// proyecto a una empresa. Solo lo que trae AndroidX.
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
}
