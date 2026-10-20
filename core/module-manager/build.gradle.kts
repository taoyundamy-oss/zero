plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zero.v2.core.modulemanager"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isReturnDefaultValues = true // Android Log only; lifecycle dependencies are explicit fakes.
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:module-api"))
    implementation(project(":core:bus"))
    implementation(project(":core:trace"))
    implementation(project(":core:data"))

    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
}
