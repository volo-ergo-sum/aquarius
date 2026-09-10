import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// Signing material for release builds, read from a file the repository does not
// contain. keystore.properties and *.jks are both in .gitignore; the keystore
// itself lives outside the working tree entirely, so a stray `git add -A` cannot
// reach it.
//
// Absent - which is the case for anyone who clones this - debug builds work
// exactly as before and assembleRelease produces an unsigned APK that Android
// refuses to install. That is the intended failure: an unsigned artifact that
// will not install is better than one signed with a key everybody has.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
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

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
            // v1 is dead weight above API 24 and this is minSdk 31. v3 is not
            // optional though: it is the scheme that carries a proof-of-rotation
            // record, so an APK signed without it can never be re-keyed - if the
            // key is ever lost or compromised, every install is stranded.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // R8 is left off. GeckoView is ~70% of the package and none of it is
            // Java that shrinking would touch; what it would touch is a few
            // hundred KB of this app and androidx, against the cost of every
            // stack trace from a user arriving obfuscated.
            isMinifyEnabled = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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

    // Google Play Services comes in here, transitively, and stays.
    //
    // GeckoView depends on play-services-fido for WebAuthn, which puts ~2,100
    // com.google.android.gms references in the dex and a GoogleApiActivity in
    // the merged manifest. In an app whose point is not depending on Google's
    // software that is galling, and excluding it does work - it was tried, the
    // build succeeds, the count drops to 40, the app launches.
    //
    // It is not shipped that way because of what those 40 are.
    // WebAuthnTokenManager holds a "static final Algorithm[]
    // SUPPORTED_ALGORITHMS", so the class touches GMS types in its static
    // initialiser: loading it at all throws NoClassDefFoundError once the
    // classes are gone. One of its entry points is
    // webAuthnIsUserVerifyingPlatformAuthenticatorAvailable, which is exactly
    // what a sign-in page calls to decide whether to offer a passkey - so the
    // failure would land in the middle of signing in, which is the one path
    // that must not break. Whether Gecko catches it across the JNI boundary or
    // takes the content process down with it is untested, and "untested" is not
    // good enough there.
    //
    // To build without it anyway, add to the dependency above:
    //     { exclude(group = "com.google.android.gms") }
    // and be aware that passkey sign-in is then a crash rather than a refusal.

    implementation("androidx.appcompat:appcompat:1.7.0")

    // Android 12 draws a splash whether or not you configure one. This is the
    // compat wrapper for the theme attributes that control it; without it the
    // Theme.SplashScreen parent does not resolve.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity:1.9.3")
}
