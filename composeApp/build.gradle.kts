import org.gradle.internal.os.OperatingSystem
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            implementation(libs.okio)
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
            implementation(libs.androidx.datastore.preferences.core)

            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.net.jna)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.swing)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "org.mlm.mages"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.mlm.mages"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"

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

val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

val rustDirDefault = rootProject.layout.projectDirectory.dir("rust")
val os = OperatingSystem.current()
val hostLibName = when {
    os.isMacOsX -> "libmages_ffi.dylib"
    os.isWindows -> "mages_ffi.dll"
    else -> "libmages_ffi.so"
}
val hostLibFile = rustDirDefault.file("target/release/$hostLibName")

val useCargoFallback = providers.provider { true }
val cargoBinDefault = providers.provider { if (os.isWindows) "cargo.exe" else "cargo" }
val vendoredManifestVar = rustDirDefault.file("uniffi-bindgen/Cargo.toml")

val cargoBuildAndroid = tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(cargoAbis)
    cargoBin.set(cargoBinDefault)
    rustDir.set(rustDirDefault)
    jniOut.set(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

val cargoBuildDesktop = tasks.register<CargoHostTask>("cargoBuildDesktop") {
    cargoBin.set(cargoBinDefault)
    rustDir.set(rustDirDefault)
    jniOut.set(layout.buildDirectory.dir("nativeLibs"))
}

val genUniFFIAndroid = tasks.register<GenerateUniFFITask>("genUniFFIAndroid") {
    dependsOn(cargoBuildAndroid)
    libraryFile.set(hostLibFile)
    configFile.set(rustDirDefault.file("uniffi.android.toml"))
    language.set("kotlin")
    uniffiPath.set("")
    useFallbackCargo.set(useCargoFallback)
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(layout.projectDirectory.dir("src/androidMain/kotlin"))
}

val genUniFFIJvm = tasks.register<GenerateUniFFITask>("genUniFFIJvm") {
    dependsOn(cargoBuildDesktop)
    libraryFile.set(hostLibFile)
    configFile.set(rustDirDefault.file("uniffi.jvm.toml"))
    language.set("kotlin")
    uniffiPath.set("")
    useFallbackCargo.set(useCargoFallback)
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(layout.projectDirectory.dir("src/jvmMain/kotlin"))
}

tasks.named("preBuild").configure {
    dependsOn(genUniFFIAndroid)
    dependsOn(cargoBuildAndroid)
}

afterEvaluate {
    tasks.findByName("compileKotlinJvm")?.dependsOn(genUniFFIJvm, cargoBuildDesktop)
}

compose.desktop {
    application {
        mainClass = "org.mlm.mages.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Mages"
            packageVersion = "0.1.0"
        }
    }
}

// Configure the run task to set jna.library.path
afterEvaluate {
    tasks.findByName("compileKotlinJvm")?.dependsOn(genUniFFIJvm, cargoBuildDesktop)

    tasks.withType<JavaExec>().configureEach {
        if (name == "run") {
            dependsOn(cargoBuildDesktop)
            val libDir = layout.buildDirectory.dir("nativeLibs").get().asFile.absolutePath
            systemProperty("jna.library.path", libDir)

            doFirst {
                logger.lifecycle("JNA library path set to: $libDir")
                val libFile = File(libDir, hostLibName)
                if (libFile.exists()) {
                    logger.lifecycle("Library found at: ${libFile.absolutePath}")
                } else {
                    logger.error("Library NOT found at: ${libFile.absolutePath}")
                }
            }
        }
    }
}

@DisableCachingByDefault(because = "Builds native code")
abstract class CargoHostTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val cargoBin: Property<String>

    @get:InputDirectory
    abstract val rustDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniOut: DirectoryProperty

    @TaskAction
    fun run() {
        val rustDirFile = rustDir.get().asFile

        logger.lifecycle("Building Rust library in: ${rustDirFile.absolutePath}")

        execOps.exec {
            workingDir = rustDirFile
            commandLine(
                cargoBin.get(),
                "build",
                "--release"
            )
        }

        val outDir = jniOut.get().asFile
        outDir.mkdirs()

        val os = OperatingSystem.current()
        val libName = when {
            os.isMacOsX -> "libmages_ffi.dylib"
            os.isWindows -> "mages_ffi.dll"
            else -> "libmages_ffi.so"
        }

        val sourceLib = rustDirFile.resolve("target/release/$libName")
        val targetLib = outDir.resolve(libName)

        if (!sourceLib.exists()) {
            throw GradleException("Library not found at ${sourceLib.absolutePath}")
        }

        logger.lifecycle("Copying ${sourceLib.absolutePath} to ${targetLib.absolutePath}")
        sourceLib.copyTo(targetLib, overwrite = true)
        logger.lifecycle("Library successfully copied")
    }
}


@DisableCachingByDefault(because = "Invokes external tool")
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


@DisableCachingByDefault(because = "Runs external tool")
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