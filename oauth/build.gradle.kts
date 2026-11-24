plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.oauth"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    constraints {
        // 与宿主应用已解析的依赖版本对齐，独立 lint/测试不能退回浏览器库声明的旧版本。
        implementation("androidx.collection:collection:1.6.0")
        implementation("androidx.activity:activity:${libs.versions.activityCompose.get()}")
        implementation("androidx.lifecycle:lifecycle-runtime:${libs.versions.lifecycleRuntimeKtx.get()}")
        implementation("androidx.navigationevent:navigationevent:1.1.2")
        implementation("androidx.savedstate:savedstate:1.4.0")
        implementation("androidx.profileinstaller:profileinstaller:${libs.versions.profileinstaller.get()}")
        implementation("androidx.compose.runtime:runtime-annotation:1.12.0-beta01")
        implementation("androidx.annotation:annotation-experimental:1.5.1")
        implementation("androidx.tracing:tracing:1.3.0")
        implementation("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.asProvider().get()}")
        implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
        implementation("org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.1")
    }
    api(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
}
