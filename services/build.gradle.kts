plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zero.v2.services"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("siwcTestApplicationId").orNull?.let {
            testApplicationId = it
            targetSdk = 35
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // v6.3-v4: 模型文件不压缩（ONNX mmap 需要 + 避免解压 446MB 内存峰值）
    androidResources {
        noCompress += listOf("onnx", "bin", "fst", "txt", "dict")
    }

    // M1.1（模块化轨）：Shizuku UserService AIDL——AGP 8.x 默认关闭，须显式开启
    buildFeatures {
        aidl = true
    }

    // P2-2：services 仅编译 Sherpa 调用代码，不产出可独立安装的制品。
    // 原生库由 app 统一打包，避免约 117MB native 库进入 services lint AAR，
    // 进而规避 Windows 下 AGP transforms 临时目录移动失败。
    sourceSets["main"].jniLibs.setSrcDirs(emptyList<String>())

    // Opt-in, independent instrumentation APK; never changes the main app's native packaging.
    if (providers.gradleProperty("p5NativeAcceptance").orNull == "true") {
        sourceSets["androidTest"].jniLibs.srcDir("src/main/jniLibs")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:module-api"))
    implementation(project(":core:bus"))
    implementation(project(":core:data"))
    implementation(project(":core:trace"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation("org.json:json:20240303")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // M1.1（模块化轨）：Shizuku 特权操作层——api=UserService 绑定/授权 API，
    // provider=ShizukuProvider（接收 Shizuku server 推送的 binder，manifest 声明见 app）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // v6.3-v4: sherpa-onnx 本地语音引擎。app 负责最终 APK 打包；services 仅需编译期类型，
    // 避免将约 117MB 本地 JAR 重复嵌入 services lint AAR，触发 Windows transforms 失败。
    compileOnly(files("libs/sherpa-onnx.jar"))
    compileOnly(files("libs/onnxruntime-1.17.1.jar"))
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.ktx)
    androidTestImplementation(files("libs/onnxruntime-1.17.1.jar"))
}
