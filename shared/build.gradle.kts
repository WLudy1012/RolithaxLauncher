import java.io.File

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("com.android.library")
}

kotlin {
    androidTarget()
    jvm("desktop")
    if (providers.gradleProperty("rolithax.windowsNative").orNull == "true") {
        mingwX64 {
            binaries.sharedLib { baseName = "rolithax_core" }
        }
        sourceSets.named("mingwX64Main") {
            dependencies { implementation("io.ktor:ktor-client-winhttp:2.3.13") }
        }
    }

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
        val desktopMain by getting {
            dependencies { implementation("net.java.dev.jna:jna:5.15.0") }
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

val schemaSource = rootProject.layout.projectDirectory.dir("native/schema")
val schemaBuild = layout.buildDirectory.dir("schema-native")
val schemaBin = schemaBuild.map { it.dir("bin") }
val cmakeExecutable = providers.gradleProperty("rolithax.cmake").orElse(providers.environmentVariable("CMAKE_EXECUTABLE")).orElse("cmake")

val configureSchemaNative by tasks.registering(Exec::class) {
    inputs.file(schemaSource.file("CMakeLists.txt"))
    outputs.file(schemaBuild.map { it.file("CMakeCache.txt") })
    doFirst {
        val output = schemaBin.get().asFile.absolutePath.replace('\\', '/')
        commandLine(cmakeExecutable.get(), "-S", schemaSource.asFile.absolutePath, "-B", schemaBuild.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=$output", "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE=$output",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$output", "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY_RELEASE=$output",
            "-DROLITHAX_SCHEMA_JNI=ON", "-DJAVA_HOME=${providers.environmentVariable("JAVA_HOME").orElse(System.getProperty("java.home")).get()}")
    }
}

val buildSchemaNative by tasks.registering(Exec::class) {
    dependsOn(configureSchemaNative)
    inputs.files(fileTree(schemaSource) { exclude("build*/**", "**/__pycache__/**", "*.md") })
    outputs.dir(schemaBin)
    doFirst { commandLine(cmakeExecutable.get(), "--build", schemaBuild.get().asFile.absolutePath, "--config", "Release", "--parallel", "4") }
}

tasks.register<Exec>("testSchemaNative") {
    dependsOn(buildSchemaNative)
    doFirst {
        val cmake = File(cmakeExecutable.get())
        val ctest = if (cmake.isAbsolute) File(cmake.parentFile, if (cmake.name.endsWith(".exe")) "ctest.exe" else "ctest").absolutePath else "ctest"
        commandLine(ctest, "--test-dir", schemaBuild.get().asFile.absolutePath, "-C", "Release", "--output-on-failure")
    }
}

tasks.withType<Test>().configureEach {
    if (name == "desktopTest") {
        dependsOn(buildSchemaNative)
        systemProperty("jna.library.path", schemaBin.get().asFile.absolutePath)
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("MingwX64") }.configureEach {
    dependsOn(buildSchemaNative)
}
