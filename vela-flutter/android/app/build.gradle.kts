plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    // Java glue lives here (android/app/src/main/java/com/velasim/app); the
    // generated Kotlin scaffold keeps its own com.velasim.velasim_app package.
    namespace = "com.velasim.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The Java sources carry Chinese comments; Gradle's default is the platform
        // encoding, which is GBK on the Windows build host and fails the compile.
        encoding = "UTF-8"
    }

    defaultConfig {
        applicationId = "com.velasim.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // The packaged engine is linux-aarch64 glibc only, so an x86 device could
        // install the APK but never run it; keep the ABI list honest.
        ndk { abiFilters += listOf("arm64-v8a") }
        // 26 keeps the same floor as the sibling SimpSim build; the payload exec path
        // (jniLibs loader + static stub) needs no debuggable and no Shizuku at all.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // The engine and toolchain executables ship as jniLibs (libvela_*.so): the
    // installer extracts them to /data/app/<pkg>/lib/<abi>/ where our own process may
    // exec / mmap them, which the private data dir does not allow. Nothing here is a
    // real library -- the names just have to look like .so files to the packager.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // These payloads are prebuilt third-party ELFs; the NDK stripper must not
            // touch them (and would refuse the x86_64 guest libs under arm64-v8a).
            keepDebugSymbols += "**/libvela_*.so"
        }
    }

    androidResources {
        // lib64/*.so are the emulator's own shared libraries. Stored, not deflated:
        // they are ~40 MB and get streamed to files/ at first run either way, and
        // keeping them uncompressed also stops aapt2 from touching .gz/.tar members.
        noCompress += "so"
        // The aiot-toolkit dependency tree is a 311 MB tar unpacked at runtime.
        // A compressed asset this size cannot be streamed reliably by AssetManager
        // (the sibling SimpSim app hit exactly this and silently extracted nothing),
        // and aapt2 also rewrites `.gz`/`.tar` members — so store it verbatim.
        noCompress += "tar"
        // The Termux node binary has no extension, so the suffix rules above miss it
        // and aapt2 deflates it; a 48 MB compressed asset is exactly the case that
        // streamed short in SimpSim. Store it.
        noCompress += "node"
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // Deliberately NOT isDebuggable: Flutter's Gradle plugin reads the build
            // type's debuggable flag as "which build mode", so turning it on here ships
            // a JIT kernel_blob instead of libapp.so and renames the output. The flag the
            // engine needs is declared in AndroidManifest.xml instead.
        }
    }

}


kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
