plugins {
    kotlin("multiplatform") version "2.0.21-KBA-010"
}

kotlin {
    ohosArm64 {
        binaries.sharedLib {
            baseName = "rolithax_poc"
        }
    }
    sourceSets.commonMain {
        kotlin.srcDir("../../shared/src/commonMain/kotlin/com/wludy/rolithax/launcher/data/resources")
    }
}
