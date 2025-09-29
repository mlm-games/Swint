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
            implementation(libs.material.icons.extended)
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
            implementation(libs.net.jna)

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

val cargoBuildAndroid = tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(cargoAbis)
    cargoBin.set(if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
    rustDir.set(rootProject.layout.projectDirectory.dir("rust"))
    jniOut.set(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

val cargoBuildDesktop = tasks.register<CargoHostTask>("cargoBuildDesktop") {
    cargoBin.set(if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
    rustDir.set(rootProject.layout.projectDirectory.dir("rust"))
}

@DisableCachingByDefault(because = "Runs external tool; outputs are tracked")
abstract class GenerateUniFFITask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:InputFile
    abstract val libraryFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val configFile: RegularFileProperty

    @get:Input
    abstract val language: Property<String>

    @get:Input
    abstract val uniffiPath: Property<String>

    // Fallback via cargo run (resolved at configuration time)
    @get:Input
    abstract val useFallbackCargo: Property<Boolean>

    @get:Input
    abstract val cargoBin: Property<String>

    @get:Optional
    @get:InputFile
    abstract val vendoredManifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val lib = libraryFile.get().asFile
        val cfg = configFile.orNull?.asFile

        val exePath = uniffiPath.orNull?.takeIf { it.isNotBlank() }
        val exe = exePath?.let { File(it) }?.takeIf { it.exists() && it.canExecute() }

        val cmd = mutableListOf<String>()
        val workDir: File

        if (exe != null) {
            // Use the standalone binary, but run it from a Cargo dir so any internal
            // `cargo metadata` calls succeed.
            cmd += exe.absolutePath
            workDir = libraryFile.get().asFile.parentFile // rust/target/release
                ?.parentFile        // rust/target
                ?.parentFile        // rust
                ?: project.layout.projectDirectory.asFile
        } else {
            if (!useFallbackCargo.get()) {
                throw GradleException(
                    "uniffi-bindgen not found. Set UNIFFI_BINDGEN or enable cargo fallback."
                )
            }
            val manifest = vendoredManifest.orNull?.asFile
            if (manifest == null || !manifest.exists()) {
                throw GradleException(
                    "Vendored uniffi-bindgen not found at rust/uniffi-bindgen/Cargo.toml."
                )
            }
            cmd += listOf(
                cargoBin.get(), "run", "--release",
                "--manifest-path", manifest.absolutePath,
                "--bin", "uniffi-bindgen", "--"
            )
            // Run cargo from the vendored crate directory
            workDir = manifest.parentFile
        }

        cmd += listOf(
            "generate",
            "--library", lib.absolutePath,
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
val os = OperatingSystem.current()
val hostLibName = when {
    os.isMacOsX -> "libfrair_ffi.dylib"
    os.isWindows -> "frair_ffi.dll"
    else -> "libfrair_ffi.so"
}
val hostLibFile = rustDir.file("target/release/$hostLibName")

val useCargoFallback = providers.provider { true }
val cargoBinDefault = providers.provider { if (os.isWindows) "cargo.exe" else "cargo" }
val vendoredManifestVar = rustDir.file("uniffi-bindgen/Cargo.toml")

val genUniFFIAndroid = tasks.register<GenerateUniFFITask>("genUniFFIAndroid") {
    libraryFile.set(hostLibFile)
    configFile.set(rustDir.file("uniffi.android.toml"))
    language.set("kotlin")
    uniffiPath.set("")                      // force cargo fallback
    useFallbackCargo.set(useCargoFallback)  // true
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(layout.projectDirectory.dir("src/androidMain/kotlin"))
    dependsOn(cargoBuildDesktop)
}

val genUniFFIJvm = tasks.register<GenerateUniFFITask>("genUniFFIJvm") {
    libraryFile.set(hostLibFile)
    configFile.set(rustDir.file("uniffi.jvm.toml"))
    language.set("kotlin")
    uniffiPath.set("")                      // force cargo fallback
    useFallbackCargo.set(useCargoFallback)  // true
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(layout.projectDirectory.dir("src/jvmMain/kotlin"))
    dependsOn(cargoBuildDesktop)
}

// Ensure bindings + Android .so exist before compiling Kotlin/packaging
tasks.named("preBuild").configure {
    dependsOn(genUniFFIAndroid)
    dependsOn(genUniFFIJvm)
    dependsOn(cargoBuildAndroid)
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

