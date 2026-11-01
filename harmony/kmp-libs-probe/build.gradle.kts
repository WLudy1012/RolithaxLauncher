plugins { kotlin("multiplatform") version "2.0.21-KBA-010" }

val probe = providers.gradleProperty("probe").get()

kotlin {
    ohosArm64 {
        if (probe == "kuikly") binaries.sharedLib { baseName = "kuikly_dependencies_probe" }
    }
    sourceSets.commonMain.dependencies {
        when (probe) {
            "coroutines" -> implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            "serialization" -> implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            "kuikly" -> {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-KBA-001")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
            }
            else -> error("Unknown probe: $probe")
        }
    }
    if (probe == "kuikly") sourceSets.commonMain { kotlin.srcDir("src/kuiklyMain/kotlin") }
}
