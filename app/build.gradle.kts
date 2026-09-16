import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.linhhan.thorsidepad"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.linhhan.thorsidepad"
        minSdk = 30
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // The native lib is compiled by the buildNative task below (ndk-build cannot cope with the
    // spaces in this repo's path). Its output dir is registered as a jniLibs source.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("native/jniLibs"))

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload build for one device: sign with the debug key so `adb install -r` upgrades in place.
            signingConfig = signingConfigs.getByName("debug")
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
