import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.a99.wifikill"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.a99.wifikill"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.3.1"
    }

    signingConfigs {
        // Resolve signing credentials from keystore.properties (local) or
        // environment variables (CI). Neither the keystore nor the passwords
        // are committed to the repository.
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) FileInputStream(f).use { load(it) }
        }
        val storeFile = props.getProperty("storeFile") ?: System.getenv("KEYSTORE_PATH")
        val storePassword = props.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
        val keyAlias = props.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
        val keyPassword = props.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")

        if (!storeFile.isNullOrBlank() && !storePassword.isNullOrBlank()) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias ?: "wifikill"
                this.keyPassword = keyPassword ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Sign both debug and release with the same release key when one is
    // configured, so debug and release builds are interchangeable.
    val releaseSigning = signingConfigs.findByName("release")
    if (releaseSigning != null) {
        buildTypes.getByName("release").signingConfig = releaseSigning
        buildTypes.getByName("debug").signingConfig = releaseSigning
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
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
