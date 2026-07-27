plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.novelforge.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novelforge.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // Wird in der CI aus den Repo-Secrets befuellt (Keystore nie im Repo).
            val ksPath = System.getenv("RELEASE_STORE_FILE")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Mit Secret -> stabile Release-Signatur (upgrade-faehig, direkt installierbar).
            // Ohne Secret -> Debug-Signatur als Fallback, damit der Build nie unsigniert/kaputt ist.
            val ksPath = System.getenv("RELEASE_STORE_FILE")
            signingConfig = if (ksPath != null && file(ksPath).exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
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
        compose = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Shizuku: verschafft der App ADB-Rechte (ohne Root). Damit kann NovelForge das
    // ECHTE Chrome des Nutzers steuern – also die dort bereits bestehende Amazon-/KDP-
    // Anmeldung nutzen, genau wie die Desktop-Version. Optional: fehlt Shizuku, läuft
    // der Upload weiter über die eingebaute WebView.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Verschlüsselte Ablage der KDP-Zugangsdaten (hardwaregestützter Schlüssel im
    // Android-Keystore). Nur dafür – niemals Klartext in Einstellungen oder Dateien.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
