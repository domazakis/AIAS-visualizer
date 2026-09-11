import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Το agent ID διαβάζεται από το `local.properties`, που είναι στο .gitignore.
 * Το repo είναι δημόσιο: όποιος βρει το ID μπορεί να μιλάει στον agent και να
 * καίει τα λεπτά του κατόχου. Δες το `local.properties.example`.
 */
val agentId: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("aias.agentId", "")

android {
    namespace = "gr.aias.carviz"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.aias.carviz"
        minSdk = 24
        targetSdk = 34
        versionCode = 16
        versionName = "0.16-sync"
        buildConfigField("String", "AGENT_ID", "\"$agentId\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.8.4")
    // Το java.net.http.WebSocket θέλει API 33· εμείς στηρίζουμε από 24.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
