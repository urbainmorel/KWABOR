plugins {
    base
    id("com.diffplug.spotless") version "6.25.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("com.android.application") version "9.2.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.2.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.7" apply false
    id("com.google.firebase.firebase-perf") version "2.0.2" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

fun Project.configureQualityTools() {
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**/*.kt")
            ktlint().editorConfigOverride(
                mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
            )
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("spotlessCheck", "detekt")
    }
}

configureQualityTools()

subprojects {
    apply(plugin = "base")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configureQualityTools()
}
