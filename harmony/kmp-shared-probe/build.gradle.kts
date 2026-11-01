plugins {
    kotlin("multiplatform") version "2.0.21-KBA-010"
    kotlin("plugin.serialization") version "2.0.21-KBA-010"
}

kotlin {
    ohosArm64 {
        binaries.sharedLib { baseName = "rolithax_shared_probe" }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir("../../shared/src/commonMain/kotlin")
            dependencies {
                api("io.ktor:ktor-client-core:2.3.13")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}
