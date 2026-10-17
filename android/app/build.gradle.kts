plugins { id("com.android.application") }
android {
    namespace = "com.fh0tracker.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fh0tracker.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
