import com.android.build.api.dsl.ApplicationBuildType
import io.gitlab.arturbosch.detekt.Detekt
import org.w3c.dom.Element
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val firebaseConfigFile = layout.projectDirectory.file("google-services.json").asFile
if (firebaseConfigFile.isFile) {
    pluginManager.apply("com.google.gms.google-services")
    pluginManager.apply("com.google.firebase.crashlytics")
    pluginManager.apply("com.google.firebase.firebase-perf")
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
    buildConfigField(
        "String",
        "KWABOR_GOOGLE_WEB_CLIENT_ID",
        kwaborConfigForEnvironment(
            environment = environment,
            localSuffix = "google.webClientId",
            environmentKey = "KWABOR_GOOGLE_WEB_CLIENT_ID",
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
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-installations")
    implementation("com.google.firebase:firebase-perf")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
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

val firebasePrivacyManifestVariants =
    mapOf(
        "debug" to "Debug",
        "staging" to "Staging",
        "release" to "Release",
    )
val firebaseDisabledCollectionMetadata =
    setOf(
        "firebase_analytics_collection_enabled",
        "firebase_data_collection_default_enabled",
        "firebase_crashlytics_collection_enabled",
        "firebase_performance_collection_enabled",
        "google_analytics_adid_collection_enabled",
        "google_analytics_default_allow_ad_personalization_signals",
    )
val firebaseForbiddenAttributionPermissions =
    setOf(
        "android.permission.ACCESS_ADSERVICES_AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "com.google.android.gms.permission.AD_ID",
    )
val expectedAndroidBackupDomains =
    setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )
val expectedAndroidBackupResourcePaths =
    setOf(
        "xml/backup_rules.xml",
        "xml/data_extraction_rules.xml",
    )

val verifyFirebaseMergedManifests by tasks.registering {
    group = "verification"
    description = "Verifies Firebase and local-backup privacy defaults in every Android variant."
    firebasePrivacyManifestVariants.values.forEach { variantName ->
        dependsOn("process${variantName}MainManifest")
        dependsOn("package${variantName}Resources")
    }

    doLast {
        firebasePrivacyManifestVariants.forEach { (variantDirectory, variantName) ->
            val manifestFile =
                layout.buildDirectory
                    .file(
                        "intermediates/merged_manifest/$variantDirectory/" +
                            "process${variantName}MainManifest/AndroidManifest.xml",
                    ).get()
                    .asFile
            check(manifestFile.isFile) {
                "Missing merged Android manifest for $variantDirectory: ${manifestFile.absolutePath}"
            }
            val documentBuilderFactory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            val document = documentBuilderFactory.newDocumentBuilder().parse(manifestFile)
            val androidNamespace = "http://schemas.android.com/apk/res/android"

            fun elements(tagName: String): List<Element> {
                val nodes = document.getElementsByTagName(tagName)
                return buildList {
                    for (index in 0 until nodes.length) {
                        (nodes.item(index) as? Element)?.let(::add)
                    }
                }
            }

            val applications = elements("application")
            check(applications.size == 1) {
                "$variantDirectory merged manifest must contain exactly one application"
            }
            val application = applications.single()
            val expectedBackupAttributes =
                mapOf(
                    "allowBackup" to "false",
                    "fullBackupContent" to "@xml/backup_rules",
                    "dataExtractionRules" to "@xml/data_extraction_rules",
                )
            expectedBackupAttributes.forEach { (attributeName, expectedValue) ->
                check(application.getAttributeNS(androidNamespace, attributeName) == expectedValue) {
                    "$variantDirectory merged manifest must set android:$attributeName=$expectedValue"
                }
            }
            check(application.getAttributeNS(androidNamespace, "backupAgent").isBlank()) {
                "$variantDirectory merged manifest must not install a custom BackupAgent"
            }

            val permissionNames =
                (elements("uses-permission") + elements("uses-permission-sdk-23"))
                    .map { element -> element.getAttributeNS(androidNamespace, "name") }
            val forbiddenPermissions = permissionNames.toSet() intersect firebaseForbiddenAttributionPermissions
            check(forbiddenPermissions.isEmpty()) {
                "$variantDirectory merged manifest contains forbidden attribution permissions: " +
                    forbiddenPermissions.sorted().joinToString()
            }

            val providerNames =
                elements("provider").map { element -> element.getAttributeNS(androidNamespace, "name") }
            check("com.google.firebase.provider.FirebaseInitProvider" !in providerNames) {
                "$variantDirectory merged manifest still contains FirebaseInitProvider"
            }

            val libraryNames =
                elements("uses-library").map { element -> element.getAttributeNS(androidNamespace, "name") }
            check("android.ext.adservices" !in libraryNames) {
                "$variantDirectory merged manifest still contains android.ext.adservices"
            }

            val metadata = elements("meta-data")
            firebaseDisabledCollectionMetadata.forEach { metadataName ->
                val values =
                    metadata
                        .filter { element ->
                            element.getAttributeNS(androidNamespace, "name") == metadataName
                        }.map { element -> element.getAttributeNS(androidNamespace, "value") }
                check(values == listOf("false")) {
                    "$variantDirectory merged manifest must contain exactly one $metadataName=false"
                }
            }

            val packagedResourcesDirectory =
                layout.buildDirectory
                    .dir(
                        "intermediates/packaged_res/$variantDirectory/" +
                            "package${variantName}Resources",
                    ).get()
                    .asFile
            check(packagedResourcesDirectory.isDirectory) {
                "Missing packaged Android resources for $variantDirectory: " +
                    packagedResourcesDirectory.absolutePath
            }
            val backupResourceFiles =
                packagedResourcesDirectory
                    .walkTopDown()
                    .filter(File::isFile)
                    .filter { file ->
                        file.name == "backup_rules.xml" ||
                            file.name == "data_extraction_rules.xml"
                    }.toList()
            val backupResourcePaths =
                backupResourceFiles
                    .map { file ->
                        file.relativeTo(packagedResourcesDirectory).invariantSeparatorsPath
                    }.toSet()
            check(backupResourcePaths == expectedAndroidBackupResourcePaths) {
                "$variantDirectory packaged backup rules must exist only in unqualified xml/: " +
                    backupResourcePaths.sorted().joinToString()
            }

            fun parsePackagedXml(resourcePath: String): Element {
                val resourceFile = packagedResourcesDirectory.resolve(resourcePath)
                check(resourceFile.isFile) {
                    "$variantDirectory is missing packaged resource $resourcePath"
                }
                return documentBuilderFactory
                    .newDocumentBuilder()
                    .parse(resourceFile)
                    .documentElement
            }

            fun childElements(parent: Element): List<Element> =
                buildList {
                    val nodes = parent.childNodes
                    for (index in 0 until nodes.length) {
                        (nodes.item(index) as? Element)?.let(::add)
                    }
                }

            fun verifyBackupExclusions(
                parent: Element,
                label: String,
            ) {
                val exclusions = childElements(parent)
                check(exclusions.all { it.tagName == "exclude" }) {
                    "$variantDirectory $label must contain only exclude elements"
                }
                check(exclusions.all { it.attributes.length == 2 }) {
                    "$variantDirectory $label excludes must declare only domain and path"
                }
                val values =
                    exclusions.map { exclusion ->
                        exclusion.getAttribute("domain") to exclusion.getAttribute("path")
                    }
                val expectedValues = expectedAndroidBackupDomains.map { domain -> domain to "." }
                check(values.size == expectedValues.size && values.toSet() == expectedValues.toSet()) {
                    "$variantDirectory $label must exclude every audited Android data domain"
                }
            }

            val fullBackupRoot = parsePackagedXml("xml/backup_rules.xml")
            check(fullBackupRoot.tagName == "full-backup-content" && fullBackupRoot.attributes.length == 0) {
                "$variantDirectory backup_rules.xml must use an attribute-free full-backup-content root"
            }
            verifyBackupExclusions(fullBackupRoot, "backup_rules.xml")

            val extractionRoot = parsePackagedXml("xml/data_extraction_rules.xml")
            check(extractionRoot.tagName == "data-extraction-rules" && extractionRoot.attributes.length == 0) {
                "$variantDirectory data_extraction_rules.xml must use an attribute-free data-extraction-rules root"
            }
            val extractionSections = childElements(extractionRoot)
            check(
                extractionSections.map(Element::getTagName).toSet() ==
                    setOf("cloud-backup", "device-transfer") &&
                    extractionSections.size == 2,
            ) {
                "$variantDirectory data_extraction_rules.xml must contain cloud-backup and device-transfer"
            }
            extractionSections.forEach { section ->
                check(section.attributes.length == 0) {
                    "$variantDirectory ${section.tagName} must not declare weakening attributes"
                }
                verifyBackupExclusions(section, "data_extraction_rules.xml:${section.tagName}")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyFirebaseMergedManifests)
}
