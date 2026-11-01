plugins {
    kotlin("multiplatform") version "2.0.21-KBA-010"
    kotlin("plugin.serialization") version "2.0.21-KBA-010"
}

kotlin {
    ohosArm64 {
        binaries.sharedLib { baseName = "rolithax_domain" }
    }
    sourceSets.commonMain {
        kotlin.srcDir("../../shared/src/commonMain/kotlin")
        kotlin.include(
            "com/wludy/rolithax/launcher/data/resources/ResourceModels.kt",
            "com/wludy/rolithax/launcher/shared/DaemonModels.kt",
            "com/wludy/rolithax/launcher/shared/SignalRProtocol.kt",
            "com/wludy/rolithax/launcher/shared/JavaRecommendation.kt",
            "com/wludy/rolithax/launcher/shared/DaemonPayloadCodec.kt",
        )
        dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1-KBA-003")
        }
    }
}

tasks.register<Copy>("prepareHarmonyNative") {
    dependsOn("linkDebugSharedOhosArm64")
    from(layout.buildDirectory.file("bin/ohosArm64/debugShared/librolithax_domain.so"))
    into(layout.projectDirectory.dir("../entry/libs/arm64-v8a"))
}
