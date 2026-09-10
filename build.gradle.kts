plugins {
    // 9.3.2 is a floor, not a preference: androidx.core 1.19.0 comes in
    // transitively through both appcompat and GeckoView, and its aar-metadata
    // declares minAndroidGradlePluginVersion 9.1.0 and minCompileSdk 37. Older
    // AGP fails the build at checkDebugAarMetadata rather than at compile, so
    // the error does not obviously point here.
    id("com.android.application") version "9.3.2" apply false
}
