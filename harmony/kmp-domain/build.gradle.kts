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
    val cxxRuntime = providers.environmentVariable("OHOS_SDK_HOME").map { sdk ->
        file(sdk).resolve("native/llvm/lib/aarch64-linux-ohos/libc++_shared.so")
    }
    from(layout.buildDirectory.file("bin/ohosArm64/debugShared/librolithax_domain.so"))
    from(cxxRuntime)
    into(layout.projectDirectory.dir("../entry/libs/arm64-v8a"))
    doFirst {
        require(cxxRuntime.orNull?.isFile == true) { "OHOS_SDK_HOME 必须指向包含 ARM64 C++ 运行库的 SDK 目录" }
    }
}
