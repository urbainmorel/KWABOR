plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kwabor.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kwabor.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    target {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation("androidx.activity:activity-compose:1.13.0")
}
