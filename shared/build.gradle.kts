import groovy.json.JsonSlurper
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    val sharedXcFramework = XCFramework("Shared")

    android {
        namespace = "com.kwabor.shared"
        compileSdk = 36
        minSdk = 26
        withHostTest {
            isIncludeAndroidResources = true
        }
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
            implementation("androidx.datastore:datastore:1.2.1")
            implementation("androidx.datastore:datastore-preferences:1.2.1")
            implementation("androidx.room:room-runtime:2.8.4")
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
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

androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { hostTest ->
            hostTest.sources.assets?.addStaticSourceDirectory("schemas")
        }
    }
}

dependencies {
    add("androidHostTestImplementation", "androidx.room:room-testing:2.8.4")
    add("androidHostTestImplementation", "androidx.test:core:1.7.0")
    add("androidHostTestImplementation", "org.robolectric:robolectric:4.16")
    add("kspAndroid", "androidx.room:room-compiler:2.8.4")
    add("kspIosArm64", "androidx.room:room-compiler:2.8.4")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.8.4")
    add("kspIosX64", "androidx.room:room-compiler:2.8.4")
}

room {
    schemaDirectory("$projectDir/schemas")
}

val roomSchemaDirectory = layout.projectDirectory.dir("schemas")

val verifyRoomSchemas by tasks.registering {
    group = "verification"
    description = "Validates Room schema history and rejects schema drift in CI."
    dependsOn("copyRoomSchemas")
    inputs.dir(roomSchemaDirectory)

    doLast {
        val schemaFiles =
            roomSchemaDirectory.asFileTree
                .matching { include("**/*.json") }
                .files
                .sortedBy { schemaFile -> schemaFile.invariantSeparatorsPath }
        check(schemaFiles.isNotEmpty()) { "At least one exported Room schema JSON is required." }

        schemaFiles.groupBy { schemaFile -> schemaFile.parentFile }.forEach { (databaseDirectory, files) ->
            val versions =
                files.map { schemaFile ->
                    val fileVersion =
                        schemaFile.nameWithoutExtension.toIntOrNull()
                            ?: error("Room schema filenames must be integer versions: ${schemaFile.name}")
                    val document = JsonSlurper().parse(schemaFile) as Map<*, *>
                    val database =
                        document["database"] as? Map<*, *>
                            ?: error("Room schema is missing its database object: ${schemaFile.name}")
                    val declaredVersion =
                        (database["version"] as? Number)?.toInt()
                            ?: error("Room schema is missing its database version: ${schemaFile.name}")
                    check(declaredVersion == fileVersion) {
                        "Room schema filename version $fileVersion differs from declared version $declaredVersion."
                    }
                    val identityHash = database["identityHash"] as? String
                    check(!identityHash.isNullOrBlank()) { "Room schema identityHash is required: ${schemaFile.name}" }
                    fileVersion
                }.sorted()
            check(versions == (1..versions.last()).toList()) {
                "Room schema history must start at 1 without gaps in ${databaseDirectory.name}: $versions"
            }
        }

        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            val process =
                ProcessBuilder(
                    "git",
                    "status",
                    "--porcelain=v1",
                    "--untracked-files=all",
                    "--",
                    "shared/schemas",
                )
                    .directory(rootProject.projectDir)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
            check(process.waitFor() == 0) { "Unable to inspect tracked Room schemas:\n$output" }
            check(output.isBlank()) {
                "Room schemas are stale or untracked after generation:\n$output"
            }
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
    dependsOn(verifyDomainPurity, verifyRoomSchemas)
}
