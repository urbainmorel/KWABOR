plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        outputModuleName = "kwabor-web"
        browser {
            commonWebpackConfig {
                outputFileName = "kwabor-web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.runtime)
        }
    }
}
