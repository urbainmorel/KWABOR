import com.android.build.api.dsl.ApplicationBuildType
import io.gitlab.arturbosch.detekt.Detekt
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.firebase.crashlytics")
    id("com.google.firebase.firebase-perf")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val firebaseConfigFile = layout.projectDirectory.file("google-services.json").asFile
if (firebaseConfigFile.isFile) {
    pluginManager.apply("com.google.gms.google-services")
}

val aggregateArtifactTaskNames = setOf("assemble", "bundle", "build")
val releaseArtifactTaskPrefixes = setOf("assemble", "bundle", "package", "sign")
val versionNamePattern = "^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$"

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

val kwaborEnvironment =
    kwaborConfig(
        localKey = "kwabor.environment",
        environmentKey = "KWABOR_ENVIRONMENT",
    ).ifBlank { "development" }

require(kwaborEnvironment in setOf("development", "staging", "production")) {
    "kwabor.environment must be development, staging, or production."
}

fun kwaborConfigForEnvironment(
    environment: String,
    localSuffix: String,
    environmentKey: String,
): String {
    val environmentSpecificValue =
        kwaborConfig(
            localKey = "kwabor.$environment.$localSuffix",
            environmentKey = "${environmentKey}_${environment.uppercase()}",
        )
    if (environmentSpecificValue.isNotBlank()) {
        return environmentSpecificValue
    }
    return if (kwaborEnvironment == environment) {
        kwaborConfig(
            localKey = "kwabor.$localSuffix",
            environmentKey = environmentKey,
        )
    } else {
        ""
    }
}

fun ApplicationBuildType.configureKwaborEnvironment(
    environment: String,
    appLabel: String,
) {
    buildConfigField("String", "KWABOR_ENVIRONMENT", environment.asBuildConfigString())
    buildConfigField(
        "String",
        "KWABOR_SUPABASE_URL",
        kwaborConfigForEnvironment(
            environment = environment,
            localSuffix = "supabase.url",
            environmentKey = "KWABOR_SUPABASE_URL",
        ).asBuildConfigString(),
    )
    buildConfigField(
        "String",
        "KWABOR_SUPABASE_PUBLISHABLE_KEY",
        kwaborConfigForEnvironment(
            environment = environment,
            localSuffix = "supabase.publishableKey",
            environmentKey = "KWABOR_SUPABASE_PUBLISHABLE_KEY",
        ).asBuildConfigString(),
    )
    resValue("string", "app_name", appLabel)
}

data class AndroidSigningCredentials(
    val storePath: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val signingValues =
    listOf(
        kwaborConfig("kwabor.android.signing.storePath", "KWABOR_ANDROID_KEYSTORE_PATH"),
        kwaborConfig("kwabor.android.signing.storePassword", "KWABOR_ANDROID_KEYSTORE_PASSWORD"),
        kwaborConfig("kwabor.android.signing.keyAlias", "KWABOR_ANDROID_KEY_ALIAS"),
        kwaborConfig("kwabor.android.signing.keyPassword", "KWABOR_ANDROID_KEY_PASSWORD"),
    )
val configuredSigningValueCount = signingValues.count(String::isNotBlank)
require(configuredSigningValueCount == 0 || configuredSigningValueCount == signingValues.size) {
    "Android release signing must provide store path, store password, key alias, and key password together."
}
val releaseSigningCredentials =
    if (configuredSigningValueCount == signingValues.size) {
        AndroidSigningCredentials(
            storePath = signingValues[0],
            storePassword = signingValues[1],
            keyAlias = signingValues[2],
            keyPassword = signingValues[3],
        )
    } else {
        null
    }
releaseSigningCredentials?.let { credentials ->
    require(rootProject.file(credentials.storePath).isFile) {
        "The configured Android release keystore does not exist."
    }
}

val kwaborVersionCode =
    requireNotNull(
        kwaborConfig("kwabor.versionCode", "KWABOR_VERSION_CODE").ifBlank { "1" }.toIntOrNull(),
    ) { "kwabor.versionCode must be a positive integer." }
require(kwaborVersionCode > 0) { "kwabor.versionCode must be a positive integer." }
val kwaborVersionName = kwaborConfig("kwabor.versionName", "KWABOR_VERSION_NAME").ifBlank { "0.1.0" }
require(Regex(versionNamePattern).matches(kwaborVersionName)) {
    "kwabor.versionName must use a semantic version such as 1.0.0 or 1.0.0-rc.1."
}

val releaseArtifactRequested =
    gradle.startParameter.taskNames.any { taskName ->
        val taskPathSegments = taskName.trim(':').split(':')
        val simpleTaskName = taskPathSegments.last().lowercase()
        val targetsAndroidApp =
            taskPathSegments.size == 1 || taskPathSegments.dropLast(1).lastOrNull() == "androidApp"
        targetsAndroidApp &&
            (
                simpleTaskName in aggregateArtifactTaskNames ||
                    (
                        "release" in simpleTaskName &&
                            releaseArtifactTaskPrefixes.any(simpleTaskName::startsWith)
                    )
            )
    }
require(!releaseArtifactRequested || releaseSigningCredentials != null) {
    "A release artifact requires the injected Android upload keystore credentials."
}

android {
    namespace = "com.kwabor.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kwabor.android"
        minSdk = 26
        targetSdk = 36
        versionCode = kwaborVersionCode
        versionName = kwaborVersionName
    }

    val releaseUploadSigning =
        releaseSigningCredentials?.let { credentials ->
            signingConfigs.create("releaseUpload") {
                storeFile = rootProject.file(credentials.storePath)
                storePassword = credentials.storePassword
                keyAlias = credentials.keyAlias
                keyPassword = credentials.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }

    buildTypes {
        getByName("debug") {
            configureKwaborEnvironment(environment = "development", appLabel = "Kwabor Dev")
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            configureKwaborEnvironment(environment = "production", appLabel = "Kwabor")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            releaseUploadSigning?.let { signingConfig = it }
        }
        create("staging") {
            configureKwaborEnvironment(environment = "staging", appLabel = "Kwabor Staging")
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-staging"
            matchingFallbacks += "release"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
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
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    implementation(compose.foundation)
    implementation(compose.components.uiToolingPreview)
    implementation(compose.materialIconsExtended)
    implementation(compose.material3)
    implementation(compose.runtime)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-perf")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

val detektUnitTest by tasks.registering(Detekt::class) {
    description = "Runs Detekt on Android application unit tests."
    setSource(fileTree("src/test/kotlin") { include("**/*.kt") })
}

tasks.named("detekt") {
    dependsOn(detektUnitTest)
}
