import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    val sharedXcFramework = XCFramework("Shared")

    android {
        namespace = "com.kwabor.shared"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvmToolchain(21)

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            sharedXcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.foundation)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.materialIconsExtended)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation("io.insert-koin:koin-core:4.2.2")
            implementation("io.github.jan-tennert.supabase:auth-kt:3.6.0")
            implementation("io.github.jan-tennert.supabase:postgrest-kt:3.6.0")
            implementation("io.github.jan-tennert.supabase:supabase-kt:3.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }

        androidMain.dependencies {
            implementation("androidx.security:security-crypto:1.1.0")
            implementation("io.ktor:ktor-client-okhttp:3.4.3")
        }

        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.4.3")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("app.cash.turbine:turbine:1.2.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
