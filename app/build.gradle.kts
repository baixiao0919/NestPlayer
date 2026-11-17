import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cloudnest.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cloudnest.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.1"
        multiDexEnabled = true
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // 签名口令不写进代码：从 local.properties（已 gitignore）或环境变量读取。
    // 应用方式见 README「自己构建」一节。
    val keystoreFile = File(rootDir, "nestplayer.keystore")
    val localProps = Properties().apply {
        val f = File(rootDir, "local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storePass = localProps.getProperty("keystore.password") ?: System.getenv("KEYSTORE_PASSWORD").orEmpty()
    val keyPass = localProps.getProperty("key.password") ?: System.getenv("KEY_PASSWORD").orEmpty()
    val alias = localProps.getProperty("key.alias") ?: System.getenv("KEY_ALIAS") ?: "nestplayer"

    signingConfigs {
        create("release") {
            if (keystoreFile.exists() && storePass.isNotEmpty()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreFile.exists() && storePass.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (keystoreFile.exists() && storePass.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/INDEX.LIST"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 播放内核
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // 图片
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // FTP
    implementation("commons-net:commons-net:3.9.0")

    // NFS(v3)
    implementation("com.emc.ecs:nfs-client:1.1.0")
}
