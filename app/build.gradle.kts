plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}
android {
    // Formal resource acceptance needs the controller compiled like the benchmark target.
    testBuildType = providers.gradleProperty("acceptanceTestBuildType").orElse("debug").get()
    namespace = "com.zero.v2.app"
    compileSdk = 35
    // S8.1（SR10/C6/F3）：版本归零——versionName 1.0.0；versionCode 1→2 保持单调（降版升级安装不被拒）
    defaultConfig {
        applicationId = "com.zero.v2"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "1.0.0"
        testInstrumentationRunner = "com.zero.v2.app.acceptance.ProviderAcceptanceRunner"
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }
    // S8.1：BuildConfig 启用（关于页版本显示读取 VERSION_NAME/VERSION_CODE）
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    // P2-2：最终 APK 是 Sherpa 原生库的唯一打包边界；资产仍保留在 services
    // 的既有目录，避免迁移大文件，同时不再放大 services 的 lint AAR。
    sourceSets["main"].jniLibs.srcDir("../services/src/main/jniLibs")
    if (testBuildType == "benchmark") {
        // Offline resource fixtures compile the debug-only acceptance helpers into the
        // controller, not the production APK. Provider-backed fixtures remain debug-only.
        sourceSets["androidTest"].java.srcDir("src/debug/java")
    }
}
dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // KB3.3（R5 显式声明 · SR15 核验产物）：sherpa-onnx JAR 在 services 模块（implementation
    // 不传递）——HotwordService（KWS 唤醒）直接引用需 app 侧同源声明；native .so 由
    // 上方 app sourceSet 直接打包进 APK（同进程共享，运行时类加载无碍）
    implementation(files("../services/libs/sherpa-onnx.jar"))
    // Manifest 由 app 直接声明 ShizukuProvider，因此 provider artifact 也由 app 直接持有；
    // services 侧依赖继续服务于 Shizuku API 实现，Gradle 会按坐标去重。
    implementation(libs.shizuku.provider)
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:module-api"))
    implementation(project(":core:module-manager"))
    implementation(project(":core:bus"))
    implementation(project(":core:trace"))
    implementation(project(":services"))
    implementation(project(":framework:chat"))
    implementation(project(":framework:memory"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)  // S8.3（图纸 D2）：启动画面（自然时长，裁定 13）
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui); implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview); implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    // Step 8.1（D57）：WebFetchModule——jsoup HTML 解析 + OkHttp HTTP 客户端
    implementation(libs.jsoup)
    implementation(libs.okhttp)

    // MU2.1（音乐轨 MD3）：Media3 播放栈——ExoPlayer（SAF content:// 播放）+ MediaSession（锁屏/蓝牙，MU2.x 后置）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Step 8.6：PdfBox-Android（PDF 文本抽取，~5MB 成熟移植）
    // v6.26d: POI 已彻底移除（运行时崩溃 xmlbeans VerifyError + 零调用——手写 ZIP+XML 替代）
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.hilt.android); ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.work.runtime.ktx); implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.junit)
    implementation(files("../services/libs/onnxruntime-1.17.1.jar"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.room.ktx)
}
