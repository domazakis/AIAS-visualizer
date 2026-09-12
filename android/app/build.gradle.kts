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
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val agentId: String = local.getProperty("aias.agentId", "")

/**
 * Το κλειδί ανεβάσματος. Ζει μόνο στο `local.properties` και στο
 * `upload-keystore.jks`, και τα δύο στο .gitignore.
 *
 * Είναι **κλειδί ανεβάσματος**, όχι το κλειδί υπογραφής της εφαρμογής: με το
 * Play App Signing η Google κρατάει το πραγματικό και υπογράφει η ίδια ό,τι
 * φτάνει στις συσκευές. Αν χαθεί αυτό εδώ, ζητάς επαναφορά από την κονσόλα
 * και ανεβάζεις καινούργιο — δεν χάνεται η εφαρμογή. Αν έλειπε το Play App
 * Signing, η απώλεια θα σήμαινε ότι δεν ξαναβγαίνει ποτέ ενημέρωση.
 */
val storeFileName: String? = local.getProperty("aias.storeFile")

android {
    namespace = "gr.aias.carviz"
    compileSdk = 35

    defaultConfig {
        applicationId = "gr.aias.carviz"
        minSdk = 24
        targetSdk = 35
        versionCode = 23
        versionName = "0.23"
        buildConfigField("String", "AGENT_ID", "\"$agentId\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (storeFileName != null) {
            create("upload") {
                storeFile = rootProject.file(storeFileName)
                storePassword = local.getProperty("aias.storePassword")
                keyAlias = local.getProperty("aias.keyAlias")
                keyPassword = local.getProperty("aias.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Χωρίς συρρίκνωση, προς το παρόν. Ο κώδικας είναι μικρός και η
            // Car App Library θέλει κανόνες διατήρησης· δεν αξίζει να μπει
            // ρίσκο ανάμεσα σε εμάς και το πρώτο ανέβασμα.
            isMinifyEnabled = false
            if (storeFileName != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
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
