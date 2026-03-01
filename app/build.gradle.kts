plugins {
    alias(libs.plugins.android.application)
}

// Capture the base versionCode here so it can be safely referenced inside
// the applicationVariants lambda below (where `this` is a variant, not android{}).
val baseVersionCode = 22

android {
    namespace        = "app.hypermtz"
    compileSdk       = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "app.hypermtz"
        minSdk        = libs.versions.minSdk.get().toInt()
        targetSdk     = libs.versions.targetSdk.get().toInt()
        versionCode   = baseVersionCode
        versionName   = "1.0.0"
    }

    signingConfigs {
        // Release signing via environment variables (CI) or a local signing.properties file.
        // Required env vars: KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
        // See README.md for local signing setup.
        create("release") {
            val keystorePath     = System.getenv("KEYSTORE_PATH")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasValue    = System.getenv("KEY_ALIAS")
            val keyPasswordValue = System.getenv("KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null
                    && keyAliasValue != null && keyPasswordValue != null) {
                storeFile     = file(keystorePath)
                storePassword = keystorePassword
                keyAlias      = keyAliasValue
                keyPassword   = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled     = false
            // applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
    }

    // ABI splits: one APK per architecture AND a fat universal APK.
    //   Per-arch  → smallest download for a known ABI (arm64, arm, x86, x86_64)
    //   Universal → single APK that installs on any device (sideloading / CI)
    splits {
        abi {
            isEnable       = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Unique versionCode per ABI so Google Play can distinguish splits.
    //   universal    = baseVersionCode * 10 + 0
    //   armeabi-v7a  = baseVersionCode * 10 + 1
    //   arm64-v8a    = baseVersionCode * 10 + 2
    //   x86          = baseVersionCode * 10 + 3
    //   x86_64       = baseVersionCode * 10 + 4
    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val abi = output.filters
                    .find { it.filterType.name == "ABI" }
                    ?.identifier
    
                val abiCode = when (abi) {
                    "armeabi-v7a" -> 1
                    "arm64-v8a"   -> 2
                    "x86"         -> 3
                    "x86_64"      -> 4
                    else          -> 0
                }
    
                output.versionCode.set(baseVersionCode * 10 + abiCode)
            }
        }
    }

    buildFeatures {
        aidl        = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
