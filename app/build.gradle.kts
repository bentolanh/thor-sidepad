import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lbento.thorsidepad"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.lbento.thorsidepad"
        minSdk = 30
        targetSdk = 33
        versionCode = 5
        versionName = "0.9.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // The native lib is compiled by the buildNative task below (ndk-build cannot cope with the
    // spaces in this repo's path). Its output dir is registered as a jniLibs source.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native/jniLibs"))

    // The release key lives outside the repo, in
    // ~/Library/Application Support/thor-sidepad/keystore.properties (storeFile, storePassword,
    // keyAlias, keyPassword). Without that file a release build falls back to the debug key.
    val keystoreProps = Properties().apply {
        val f = File(System.getProperty("user.home"), "Library/Application Support/thor-sidepad/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val ndkDir = android.ndkDirectory
val buildNative by tasks.registering(Exec::class) {
    description = "Compile jni/sidepad_native.c with the NDK clang into build/native/jniLibs/arm64-v8a."
    val src = file("src/main/jni/sidepad_native.c")
    val outDir = layout.buildDirectory.dir("native/jniLibs/arm64-v8a")
    inputs.file(src)
    outputs.dir(outDir)
    doFirst { outDir.get().asFile.mkdirs() }
    val host = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin-x86_64" else "linux-x86_64"
    executable = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin/clang").absolutePath
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--target=aarch64-linux-android30", "-shared", "-fPIC", "-O2", "-Wall",
            "-o", File(outDir.get().asFile, "libsidepad_native.so").absolutePath,
            src.absolutePath, "-llog")
    })
}
tasks.named("preBuild") { dependsOn(buildNative) }

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
