import io.gitlab.arturbosch.detekt.Detekt
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) {
            file.inputStream().use(::load)
        }
    }

fun kwaborConfig(
    localKey: String,
    environmentKey: String,
): String =
    localProperties.getProperty(localKey)
        ?: providers.gradleProperty(localKey).orNull
        ?: providers.environmentVariable(environmentKey).orNull
        ?: ""

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.kwabor.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kwabor.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            type = "String",
            name = "KWABOR_SUPABASE_URL",
            value =
                kwaborConfig(
                    localKey = "kwabor.supabase.url",
                    environmentKey = "KWABOR_SUPABASE_URL",
                ).asBuildConfigString(),
        )
        buildConfigField(
            type = "String",
            name = "KWABOR_SUPABASE_PUBLISHABLE_KEY",
            value =
                kwaborConfig(
                    localKey = "kwabor.supabase.publishableKey",
                    environmentKey = "KWABOR_SUPABASE_PUBLISHABLE_KEY",
                ).asBuildConfigString(),
        )
    }

    buildFeatures {
        buildConfig = true
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
    implementation(compose.components.uiToolingPreview)
    implementation(compose.materialIconsExtended)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0")
    testImplementation(kotlin("test-junit"))
}

val detektUnitTest by tasks.registering(Detekt::class) {
    description = "Runs Detekt on Android application unit tests."
    setSource(fileTree("src/test/kotlin") { include("**/*.kt") })
}

tasks.named("detekt") {
    dependsOn(detektUnitTest)
}
