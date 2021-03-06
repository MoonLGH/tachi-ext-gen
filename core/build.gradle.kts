plugins {
    id("com.android.library")
}

android {
    compileSdkVersion(AndroidConfig.compileSdk)

    defaultConfig {
        minSdkVersion(AndroidConfig.minSdk)
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("res"))
        }
    }

    libraryVariants.all {
        generateBuildConfigProvider?.configure {
            enabled = false
        }
    }
}
