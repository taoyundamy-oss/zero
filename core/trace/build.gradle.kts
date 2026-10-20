plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zero.v2.core.trace"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:common"))
    // v3.7: AgentTraceEventDao 持久化（D3: Trace 独立表）
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.core)
    compileOnly("javax.inject:javax.inject:1")
}
