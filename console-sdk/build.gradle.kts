plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":shared"))
}

application {
    mainClass.set("com.wludy.rolithax.console.sdk.MainKt")
    applicationName = "rolithax-console-sdk"
}

distributions {
    main {
        contents {
            from("README.md") { into("console-sdk") }
            from("spec") { into("console-sdk/spec") }
            from(rootProject.file("skills/rolithax-console-plugin")) {
                into("skills/rolithax-console-plugin")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin.compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
