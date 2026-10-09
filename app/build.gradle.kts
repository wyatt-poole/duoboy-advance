import java.util.Properties

plugins { id("com.android.application") }

// Release signing: keystore.properties in the project root, gitignored (storeFile, storePassword, keyAlias, keyPassword).
// Without it (e.g. contributors), release builds fall back to the debug key.
val keyProps = Properties().apply {
    val f = file(System.getenv("DBA_KEYSTORE_PROPS") ?: "${rootDir}/keystore.properties") // never committed
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.gbads"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.gbads"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"
        ndk { abiFilters += "arm64-v8a" } // ponytail: Thor is arm64; add x86_64 if an emulator is ever needed
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile")); storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias"); keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        // EXPERIMENTAL: unfinished features (the DexNav-style Area view); DEBUG_TOOLS: adb test broadcasts
        debug { buildConfigField("boolean", "EXPERIMENTAL", "true"); buildConfigField("boolean", "DEBUG_TOOLS", "true") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "EXPERIMENTAL", "false"); buildConfigField("boolean", "DEBUG_TOOLS", "false")
        }
        // the maintainer's test build: release speed, debug key, everything on
        create("dev") { initWith(getByName("release")); signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "EXPERIMENTAL", "true"); buildConfigField("boolean", "DEBUG_TOOLS", "true") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
