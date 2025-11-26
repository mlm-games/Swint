import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import org.gradle.internal.os.OperatingSystem
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Copy

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
            implementation(libs.connector)
            implementation(libs.connector.ui)
            implementation(libs.embedded.fcm.distributor)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.browser)
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
            implementation(compose.components.resources)
            implementation(libs.uri.kmp)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.net.jna)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
            implementation(libs.slf4j.simple)
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
        versionCode = 107
        versionName = "0.8.0"

        androidResources {
            localeFilters += setOf("en", "ar", "de", "es-rES", "es-rUS", "fr", "hr", "hu", "in", "it", "ja", "pl", "pt-rBR", "ru-rRU", "sv", "tr", "uk", "zh", "cs", "el", "fi", "ko", "nl", "vi")
        }
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")

        val enableApkSplits = (providers.gradleProperty("enableApkSplits").orNull ?: "true").toBoolean()
        val includeUniversalApk = (providers.gradleProperty("includeUniversalApk").orNull ?: "true").toBoolean()
        val targetAbi = providers.gradleProperty("targetAbi").orNull

        splits {
            abi {
                isEnable = enableApkSplits
                reset()
                if (enableApkSplits) {
                    if (targetAbi != null) {
                        include(targetAbi)
                    } else {
                        include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    }
                }
                isUniversalApk = includeUniversalApk && enableApkSplits
            }
        }

        applicationVariants.all {
            val buildingApk = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
            if (!buildingApk) return@all

            val variant = this
            outputs.all {
                if (this is ApkVariantOutputImpl) {
                    val abiName = filters.find { it.filterType == "ABI" }?.identifier
                    val base = variant.versionCode

                    if (abiName != null) {
                        // Split APKs get stable per-ABI version codes and names
                        val abiVersionCode = when (abiName) {
                            "x86" -> base - 3
                            "x86_64" -> base - 2
                            "armeabi-v7a" -> base - 1
                            "arm64-v8a" -> base
                            else -> base
                        }
                        versionCodeOverride = abiVersionCode
                        outputFileName = "mages-${variant.versionName}-${abiName}.apk"
                    } else {
                        versionCodeOverride = base + 1
                        outputFileName = "mages-${variant.versionName}-universal.apk"
                    }
                }
            }
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${rootProject.projectDir}/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
//            buildConfigField("Long", "BUILD_TIME", "0L")
//            isShrinkResources = true
        }
        getByName("debug") {
            isShrinkResources = false
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

compose.resources {
    publicResClass = true
    generateResClass = auto
}

val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

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

val targetAbiList = providers.gradleProperty("targetAbi").orNull?.let { listOf(it) } ?: cargoAbis

val cargoBuildAndroid = tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(targetAbiList)
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
    dependsOn(cargoBuildDesktop)
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

val jnaPlatformDir: String = run {
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.isLinux && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux-aarch64"
        os.isLinux -> "linux-x86-64"
        os.isMacOsX && (arch.contains("aarch64") || arch.contains("arm64")) -> "darwin-aarch64"
        os.isMacOsX -> "darwin"
        os.isWindows && arch.contains("64") -> "win32-x86-64"
        os.isWindows -> "win32-x86"
        else -> error("Unsupported OS/arch: ${System.getProperty("os.name")} $arch")
    }
}

val copyNativeForJna = tasks.register<Copy>("copyNativeForJna") {
    dependsOn(cargoBuildDesktop)
    val nativeLib = layout.buildDirectory.dir("nativeLibs").get().file(hostLibName).asFile
    from(nativeLib)
    into(file("src/jvmMain/resources/$jnaPlatformDir"))
}

tasks.named("jvmProcessResources").configure {
    dependsOn(copyNativeForJna)
}

compose.desktop {
    application {
        run {
            val libDirProvider = layout.buildDirectory.dir("nativeLibs")
            jvmArgs("-Djna.library.path=${libDirProvider.get().asFile.absolutePath}")
        }
        mainClass = "org.mlm.mages.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Mages"
            packageVersion = android.defaultConfig.versionName
            description = "Mages Matrix Client"
            vendor = "MLM Games"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            modules("java.instrument", "jdk.security.auth", "jdk.unsupported")

            linux {
                iconFile.set(project.file("../fastlane/android/metadata/en-US/images/icon.png"))
                packageName = "mages"
                debMaintainer = "gfxoxinzh@mozmail.com"
                menuGroup = "Network;InstantMessaging"
                appCategory = "Network"
            }
        }
    }
}

tasks.named("compileKotlinJvm").configure {
    dependsOn(genUniFFIJvm)
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