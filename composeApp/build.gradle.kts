import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
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

// Register the task with properties wired
val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(cargoAbis)
    cargoBin.set(if (OperatingSystem.current().isWindows) "cargo.exe" else "cargo")
    // rust/ lives at the root of the repo
    rustDir.set(rootProject.layout.projectDirectory.dir("rust"))
    // jniLibs folder is inside this module
    jniOut.set(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

// Ensure native libs are built before packaging the APK
tasks.named("preBuild").configure { dependsOn("cargoBuildAndroid") }