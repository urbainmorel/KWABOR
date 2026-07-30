import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
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
            implementation("io.insert-koin:koin-core:4.2.2")
            implementation("io.github.jan-tennert.supabase:auth-kt:3.6.0")
            implementation("io.github.jan-tennert.supabase:functions-kt:3.6.0")
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

val detektCommonTest by tasks.registering(Detekt::class) {
    description = "Runs Detekt on shared common tests."
    setSource(fileTree("src/commonTest/kotlin") { include("**/*.kt") })
}

tasks.named("detekt") {
    dependsOn(
        detektCommonTest,
        "detektMetadataCommonMain",
        "detektAndroidMain",
        "detektMetadataIosMain",
        "detektAndroidHostTest",
        "detektIosSimulatorArm64Test",
    )
}

val verifyDomainPurity by tasks.registering {
    group = "verification"
    description = "Fails when domain code leaves commonMain or imports another project layer or external library."

    val domainSources =
        fileTree("src") {
            include("*Main/kotlin/com/kwabor/shared/domain/**/*.kt")
        }
    inputs.files(domainSources)

    doLast {
        val platformDomainSources =
            domainSources
                .mapNotNull { sourceFile ->
                    val relativePath = sourceFile.relativeTo(projectDir).invariantSeparatorsPath
                    if (relativePath.startsWith("src/commonMain/")) {
                        null
                    } else {
                        "$relativePath: platform-specific domain sources are forbidden"
                    }
                }
                .sorted()
        val forbiddenImports =
            domainSources
                .flatMap { sourceFile ->
                    sourceFile.readLines().mapIndexedNotNull { index, line ->
                        val trimmedLine = line.trim()
                        if (!trimmedLine.startsWith("import ")) {
                            return@mapIndexedNotNull null
                        }
                        val importTarget =
                            trimmedLine
                                .removePrefix("import ")
                                .substringBefore(" as ")
                        val isAllowed =
                            importTarget.startsWith("kotlin.") ||
                                importTarget.startsWith("com.kwabor.shared.domain.")
                        if (isAllowed) {
                            null
                        } else {
                            "${sourceFile.relativeTo(projectDir).invariantSeparatorsPath}:${index + 1}: $trimmedLine"
                        }
                    }
                }
                .sorted()
        val violations = platformDomainSources + forbiddenImports

        check(violations.isEmpty()) {
            "The domain must remain pure Kotlin. Forbidden dependencies:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyDomainPurity)
}
