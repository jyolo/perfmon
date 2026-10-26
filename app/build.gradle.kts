import java.util.Properties

plugins {
    id("com.android.application")
}

// 口令/路径放 local.properties，不进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun lp(key: String, def: String? = null): String? = localProps.getProperty(key) ?: def

android {
    namespace = "com.jyolo.perfmon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jyolo.perfmon"
        minSdk = 26           // TYPE_APPLICATION_OVERLAY 自 API 26 起
        targetSdk = 33
        versionCode = 7
        versionName = "1.3.3"
    }

    signingConfigs {
        create("release") {
            val ks = lp("perfmon.storeFile")
            if (ks != null) {
                storeFile = rootProject.file(ks)
                storePassword = lp("perfmon.storePassword")
                keyAlias = lp("perfmon.keyAlias")
                keyPassword = lp("perfmon.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (lp("perfmon.storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
