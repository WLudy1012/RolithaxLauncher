plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("com.android.library")
}

kotlin {
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api("io.ktor:ktor-client-core:2.3.13")
            implementation("io.ktor:ktor-client-websockets:2.3.13")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:2.3.13")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        val desktopTest by getting {
            dependencies {
                implementation("io.ktor:ktor-client-cio:2.3.13")
                implementation("io.ktor:ktor-server-cio:2.3.13")
                implementation("io.ktor:ktor-server-websockets:2.3.13")
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

android {
    namespace = "com.wludy.rolithax.launcher.shared"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
