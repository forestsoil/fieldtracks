plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Stable signing (so updates install over old versions): set FT_KEYSTORE, FT_KS_PASS, FT_KEY_ALIAS, FT_KEY_PASS.
// Without them the build falls back to the debug key (fine for first install; updates need uninstall).
val ftKs: String? = System.getenv("FT_KEYSTORE")
android {
    namespace = "app.fieldtracks"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.fieldtracks"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (ftKs != null) create("ft") {
            storeFile = file(requireNotNull(ftKs))
            storePassword = System.getenv("FT_KS_PASS")
            keyAlias = System.getenv("FT_KEY_ALIAS")
            keyPassword = System.getenv("FT_KEY_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ftKs != null) signingConfigs.getByName("ft") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}
