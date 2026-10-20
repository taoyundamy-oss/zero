plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.zero.v2.framework.memory"
    compileSdk = 35; defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:module-api"))
    implementation(project(":core:bus"))
    implementation(project(":core:data"))
    implementation(project(":core:trace"))
    // v4.6-pre: EmbeddingProvider（D31 检索主径；同 framework/chat → services 先例，无环）
    implementation(project(":services"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
