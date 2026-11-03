import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// 沿用主项目锁定的 AGP、Kotlin 与 Gradle，不引入上游的新版构建插件。
android {
    namespace = "me.rerere.mediagen"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    constraints {
        implementation("androidx.collection:collection:1.6.0") {
            because("与当前应用已有的 AndroidX Collection 缓存版本对齐，避免独立模块回退到未缓存的旧版本")
        }
    }
    implementation(project(":common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
