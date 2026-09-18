import java.util.Properties

/* La firma de release NO vive en el repositorio.
   Si existe `keystore.properties` —que esta en .gitignore— se firma con el;
   si no existe, el build de release sale sin firmar y se dice por que. Asi
   cualquiera puede compilar el proyecto sin tener las claves, que es lo que
   corresponde a un proyecto de codigo abierto. */
val clavesFichero = rootProject.file("keystore.properties")
val claves = Properties().apply {
    if (clavesFichero.exists()) clavesFichero.inputStream().use { load(it) }
}
val hayFirma = claves.getProperty("storeFile") != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "red.sismo"
    compileSdk = 35

    defaultConfig {
        applicationId = "red.sismo"
        minSdk = 26                 // Android 8: servicios en primer plano
        /* Google Play exige 35 para apps nuevas desde agosto de 2025. Y no es
           solo el tramite: 35 obliga a declarar el tipo de servicio en primer
           plano y aplica el dibujo de borde a borde, que esta app ya hace. */
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-fase1"
    }

    flavorDimensions += "distribucion"
    productFlavors {
        create("libre") {
            dimension = "distribucion"
        }
        create("play") {
            dimension = "distribucion"
        }
    }

    signingConfigs {
        if (hayFirma) create("release") {
            storeFile = file(claves.getProperty("storeFile"))
            storePassword = claves.getProperty("storePassword")
            keyAlias = claves.getProperty("keyAlias")
            keyPassword = claves.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            /* R8 encoge y ofusca. Las reglas de `proguard-rules.pro` guardan lo
               que se instancia por nombre y no por codigo —las vistas del XML y
               las entidades de Room—, que es lo que R8 no puede ver. */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hayFirma) signingConfig = signingConfigs.getByName("release")
        }
        /* La de depuracion NO cambia de `applicationId` a proposito. Tenerla
           con otro paquete permitiria instalar las dos a la vez, pero hoy
           dejaria huerfanas la ficha medica y el historico del movil de
           pruebas, que es de donde salen los registros de campo. */
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        /* Apagado: no se usa en ningún sitio —la app resuelve las vistas con
           findViewById— y con activity_main.xml, que tiene cientos de ids, el
           constructor generado supera los 255 parámetros que admite Java y la
           compilación se cae. Un generador que nadie usa no puede decidir
           cuántos ids caben en una pantalla. */
        viewBinding = false
        // Para que «Acerca de» saque la versión de aquí y no de un literal.
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    testImplementation("junit:junit:4.13.2")
}
