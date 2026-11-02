import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

// Published builds (.github/workflows/android-release.yml) pass a rising build number and the shared signing
// key, so every phone can install each new build over the last. Local builds keep these defaults and this
// machine's own debug key.
val publishedVersionCode = providers.gradleProperty("mavisVersionCode").orNull?.toIntOrNull()
val publishedVersionName = providers.gradleProperty("mavisVersionName").orNull
val sharedKeystore = providers.environmentVariable("MAVIS_KEYSTORE").orNull?.let(::file)?.takeIf { it.isFile }
/** Unset or blank (an unset GitHub secret arrives as ""), the standard debug-keystore values apply. */
fun signingValue(name: String, default: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "com.mavislibrary.app"
    compileSdk = 36

    // Test-only: -PmavisUpdateTestBase=<release tag> points a DEBUG build's update check at that tag for on-device
    // auto-update proofs. Downloads stay locked to this repository's releases; release builds are unaffected.
    val updateTestBase = providers.gradleProperty("mavisUpdateTestBase").orNull
    defaultConfig {
        applicationId = "com.mavislibrary.app"
        minSdk = 26
        targetSdk = 35
        versionCode = publishedVersionCode ?: 2
        versionName = publishedVersionName ?: "0.6.0-m2"
        // Published builds are for phones only. The release workflow publishes the (release-key-signed) debug
        // variant with -PmavisVersionCode, so filter on that, not on the release build type. Leaving out the
        // emulator ABIs drops ~27 MB (x86_64 Kokoro engine). Local builds keep every ABI so the emulator still works.
        if (publishedVersionCode != null) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "API_BASE", "\"https://mavis-library.netlify.app\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://github.com/jeri2good/mavis-library/releases/latest/download/update.json\"")
        buildConfigField("String", "UPDATE_DOWNLOADS", "\"https://github.com/jeri2good/mavis-library/releases/download/\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            if (sharedKeystore != null) {
                storeFile = sharedKeystore
                storePassword = signingValue("MAVIS_KEYSTORE_PASSWORD", "android")
                keyAlias = signingValue("MAVIS_KEY_ALIAS", "androiddebugkey")
                keyPassword = signingValue("MAVIS_KEY_PASSWORD", "android")
            }
        }
    }

    buildTypes {
        debug {
            // Point a DEBUG build at one GitHub release tag's update.json (e.g. a pre-release, which /latest ignores).
            if (updateTestBase != null) buildConfigField("String", "UPDATE_MANIFEST_URL",
                "\"https://github.com/jeri2good/mavis-library/releases/download/${updateTestBase}/update.json\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // Kokoro ships for arm64-v8a + x86_64 only (the AAR also has 32-bit libs); other ABIs use the phone voice.
            excludes += listOf("lib/armeabi-v7a/libsherpa-onnx-jni.so", "lib/x86/libsherpa-onnx-jni.so", "lib/x86/libonnxruntime.so")
        }
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        lintConfig = file("lint.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.configureEach {
    // Full Guava (with its failureaccess dependency) supplies ListenableFuture; the empty compatibility artifact would duplicate it.
    exclude(group = "com.google.guava", module = "listenablefuture")
}

/**
 * Copies the bundled web build (repo-root `dist-android/`, made by `npm run build:android`)
 * into the APK's assets under `web/`. The embedded web host serves it at https://localhost/.
 */
abstract class CopyWebAssets : DefaultTask() {
    @get:Internal
    abstract val sourceDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        val src = sourceDir.get().asFile
        if (!src.resolve("index.html").isFile) {
            throw GradleException(
                "Web build not found at ${src.absolutePath}.\n" +
                    "Create it from the repository root with:  npm ci && npm run build:android\n" +
                    "(it writes dist-android/, which this app bundles as its embedded web screens)."
            )
        }
        fs.sync {
            from(src)
            into(outputDir.get().asFile.resolve("web"))
        }
    }
}

val webDist = rootProject.layout.projectDirectory.dir("../dist-android")
val copyWebAssets = tasks.register<CopyWebAssets>("copyWebAssets") {
    sourceDir.set(webDist)
    sourceFiles.from(webDist)
    outputDir.set(layout.buildDirectory.dir("generated/webAssets"))
}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyWebAssets, CopyWebAssets::outputDir)
}

/** Downloads a release asset once into Gradle's user home (shared by worktrees, works offline afterwards) and checks its SHA-256. */
abstract class FetchPinnedAar : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @TaskAction
    fun fetch() {
        val target = output.get().asFile
        if (target.isFile && digest(target) == sha256.get()) return
        target.parentFile.mkdirs()
        val part = File(target.path + ".part")
        URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val actual = digest(part)
        if (actual != sha256.get()) {
            part.delete()
            throw GradleException("SHA-256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxAar = File(gradle.gradleUserHomeDir, "caches/mavis-pinned/sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar")
val fetchSherpaOnnx = tasks.register<FetchPinnedAar>("fetchSherpaOnnx") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-static-link-onnxruntime-$sherpaOnnxVersion.aar")
    sha256.set("b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471")
    output.set(sherpaOnnxAar)
}

dependencies {
    implementation(files(sherpaOnnxAar).builtBy(fetchSherpaOnnx))
    implementation(libs.commons.compress)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.guava)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
