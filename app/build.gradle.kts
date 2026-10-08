import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
}

// Stable signing: keystore injected via GitHub Actions secrets (env vars).
// Same key on every build so app updates install over previous versions
// without INSTALL_FAILED_UPDATE_INCOMPATIBLE ("App not installed").
// Local builds without env vars fall back to debug signing automatically.
val ciStoreB64: String? = System.getenv("XFER_KEYSTORE_B64")
val ciStorePass: String? = System.getenv("XFER_STORE_PASS")
val ciKeyPass: String? = System.getenv("XFER_KEY_PASS")
val ciKeyAlias: String? = System.getenv("XFER_KEY_ALIAS")
val ciSigning: Boolean = !ciStoreB64.isNullOrBlank()

android {
    namespace = "com.threesverse.wifitransfer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.threesverse.wifitransfer"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.4.12"
    }

    signingConfigs {
        if (ciSigning) {
            create("ci") {
                val ksDir = File(System.getProperty("java.io.tmpdir"), "xfer-signing")
                ksDir.mkdirs()
                val ksFile = File(ksDir, "release.keystore")
                ksFile.writeBytes(Base64.getMimeDecoder().decode(ciStoreB64))
                storeFile = ksFile
                storePassword = ciStorePass
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ciSigning) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
}

base {
    archivesName.set("3SVerse_WiFi_Transfer")
}
