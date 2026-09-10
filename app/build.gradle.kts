plugins {
    id("com.android.application")
}

android {
    namespace = "dev.volo.aquarius"
    // 37.1, not plain 37: androidx.core 1.19.0 refuses anything below the .1
    // minor. The minor is a separate property, not part of compileSdk itself.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "dev.volo.aquarius"
        // GeckoView itself supports older releases; 31 is where this has
        // actually been run. A lower floor would be a claim nothing tests.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // arm64-v8a only. Every extra ABI is another ~200 MB APK built from
    // scratch on every assemble, because libxul.so alone is 152 MB - this is
    // not a rounding error. Add x86_64 back here if you need to run it on an
    // emulator.
    //
    // The include list is also what limits which ABIs are built at all; AGP
    // rejects ndk.abiFilters alongside it, so this is the only filter.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Engine. Published only on maven.mozilla.org (see settings.gradle.kts).
    //
    // Pinned deliberately. The whole approach rests on Gecko's default
    // User-Agent being byte-identical to Firefox for Android, so the engine
    // version is load bearing rather than an implementation detail, and a
    // floating version would swap it out from under a working sign-in.
    //
    // Expect the first build to download about 230 MB.
    implementation("org.mozilla.geckoview:geckoview:154.0.20260824154132")

    implementation("androidx.appcompat:appcompat:1.7.0")

    // Android 12 draws a splash whether or not you configure one. This is the
    // compat wrapper for the theme attributes that control it; without it the
    // Theme.SplashScreen parent does not resolve.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity:1.9.3")
}
