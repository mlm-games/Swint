import org.gradle.internal.os.OperatingSystem
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget()
    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation("net.java.dev.jna:jna:5.17.0@aar")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("net.java.dev.jna:jna:5.17.0")

        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "org.mlm.frair"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.mlm.frair"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // Include only the ABIs you need
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }

        sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

/**
 * Configuration-cache friendly Cargo + NDK task.
 * Uses typed properties and ExecOperations, no Project/script objects captured.
 */
@DisableCachingByDefault(because = "Invokes external tool; outputs are tracked via @OutputDirectory")
abstract class CargoNdkTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val cargoBin: Property<String>

    @get:InputDirectory
    abstract val rustDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniOut: DirectoryProperty

    @TaskAction
    fun run() {
        val rustDirFile = rustDir.get().asFile
        val outDir = jniOut.get().asFile
        if (!outDir.exists()) outDir.mkdirs()

        abis.get().forEach { abi ->
            execOps.exec {
                workingDir = rustDirFile
                commandLine(
                    cargoBin.get(),
                    "ndk",
                    "-t", abi,
                    "-o", outDir.absolutePath,
                    "build",
                    "--release"
                )
            }
        }
    }
}

val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(cargoAbis)
    cargoBin.set(if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
    rustDir.set(rootProject.layout.projectDirectory.dir("rust"))
    jniOut.set(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

@DisableCachingByDefault(because = "Runs external tool; outputs are tracked")
abstract class GenerateUniFFITask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:InputFile
    abstract val udlFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val configFile: RegularFileProperty

    @get:Input
    abstract val language: Property<String>

    // If empty or missing, we fall back to `cargo run --bin uniffi-bindgen --`
    @get:Input
    abstract val uniffiPath: Property<String>

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val workDir = udlFile.get().asFile.parentFile
        val cfg = configFile.orNull?.asFile
        val candidate = uniffiPath.orNull?.takeIf { it.isNotBlank() }
        val exe = candidate?.let { File(it) }?.takeIf { it.exists() && it.canExecute() }

        val cmd = mutableListOf<String>()
        val workspaceBin = File(project.rootDir, "rust/uniffi-bindgen/Cargo.toml")
        if (exe != null) {
            cmd += exe.absolutePath
        } else if (workspaceBin.exists()) {
            val cargo = if (System.getProperty("os.name").lowercase().contains("win")) "cargo.exe" else "cargo"
            cmd += listOf(cargo, "run", "--release",
                "--manifest-path", workspaceBin.absolutePath,
                "--bin", "uniffi-bindgen", "--")
        } else {
            throw GradleException(
                "uniffi-bindgen not found. Either:\n" +
                        "  1) Install it: cargo install uniffi_bindgen --version 0.29.0, then set UNIFFI_BINDGEN=\$HOME/.cargo/bin/uniffi-bindgen\n" +
                        "  2) Or vendor rust/uniffi-bindgen as described, so the task can run it via cargo."
            )
        }
        cmd += listOf(
            "generate",
            udlFile.get().asFile.absolutePath,
            "--language", language.get(),
            "--out-dir", outDir.get().asFile.absolutePath
        )
        if (cfg != null) cmd += listOf("--config", cfg.absolutePath)

        outDir.get().asFile.mkdirs()
        execOps.exec {
            workingDir = workDir
            commandLine(cmd)
        }
    }
}

val rustDir = rootProject.layout.projectDirectory.dir("rust")
val defaultBindgen = providers
    .environmentVariable("UNIFFI_BINDGEN")
    .orElse(providers.provider { "${System.getProperty("user.home")}/.cargo/bin/uniffi-bindgen" })

val genUniFFIAndroid = tasks.register<GenerateUniFFITask>("genUniFFIAndroid") {
    udlFile.set(rustDir.file("src/frair.udl"))
    configFile.set(rustDir.file("uniffi.android.toml"))
    language.set("kotlin")
    uniffiPath.set(defaultBindgen)
    outDir.set(layout.projectDirectory.dir("src/androidMain/kotlin"))
}

val genUniFFIJvm = tasks.register<GenerateUniFFITask>("genUniFFIJvm") {
    udlFile.set(rustDir.file("src/frair.udl"))
    configFile.set(rustDir.file("uniffi.jvm.toml"))
    language.set("kotlin")
    uniffiPath.set(defaultBindgen)
    outDir.set(layout.projectDirectory.dir("src/jvmMain/kotlin"))
}

/**
 * Host (Desktop) cargo build that produces libfrair_native for the current OS.
 */
@DisableCachingByDefault(because = "Builds native code; output path is deterministic")
abstract class CargoHostTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val cargoBin: Property<String>

    @get:InputDirectory
    abstract val rustDir: DirectoryProperty

    @TaskAction
    fun run() {
        val rustDirFile = rustDir.get().asFile
        execOps.exec {
            workingDir = rustDirFile
            commandLine(
                cargoBin.get(),
                "build",
                "--release"
            )
        }
    }
}

tasks.register<CargoHostTask>("cargoBuildDesktop") {
    cargoBin.set(if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
    rustDir.set(rootProject.layout.projectDirectory.dir("rust"))
}

tasks.named("preBuild").configure {
    dependsOn("cargoBuildAndroid")
    dependsOn(genUniFFIAndroid)
    dependsOn(genUniFFIJvm)
}
