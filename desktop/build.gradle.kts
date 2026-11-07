plugins {
    kotlin("jvm")
    id("org.jetbrains.compose") version "1.7.3"
    alias(libs.plugins.kotlin.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    runtimeOnly("net.java.dev.jna:jna-platform:5.15.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

val prepareDesktopLegal by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/assets/legal")) {
        into("legal")
    }
    into(layout.buildDirectory.dir("desktop-resources/common"))
}

tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("AppResources") }.configureEach {
    dependsOn(prepareDesktopLegal)
}

compose.desktop {
    application {
        mainClass = "com.wludy.rolithax.launcher.desktop.MainKt"
        nativeDistributions {
            packageName = "RolithaxLauncher"
            appResourcesRootDir.set(layout.buildDirectory.dir("desktop-resources"))
        }
    }
}
