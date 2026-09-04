import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.paddleocr"
    compileSdk = 35

        defaultConfig {
            applicationId = "com.example.paddleocr"
            minSdk = 26
            targetSdk = 35
            versionCode = 3
            versionName = "1.2.0"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包手机主流 ABI，缩小 APK（去掉 x86 模拟器镜像）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            if (props.isNotEmpty()) {
                storeFile = rootProject.file(props.getProperty("storeFile", ""))
                storePassword = props.getProperty("storePassword", "")
                keyAlias = props.getProperty("keyAlias", "")
                keyPassword = props.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 未配置 keystore.properties 时回退 debug 签名，保证任何机器都能出包
            signingConfig = if (rootProject.file("keystore.properties").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    sourceSets {
        // 把 Room 导出的 schema JSON 打进 androidTest 资产，供 MigrationTestHelper 使用
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 历史记录持久化（Room）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 端侧离线识别（ONNX Runtime + OpenCV）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    implementation("org.opencv:opencv:4.9.0")

    // 单元测试
    testImplementation("junit:junit:4.13.2")

    // 仪器化测试（迁移验证，需要连接设备/模拟器运行）
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// Room schema 导出目录（用于 MigrationTestHelper 读取历史版本 schema）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
