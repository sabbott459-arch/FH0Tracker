plugins { id("com.android.application") }
android {
    namespace = "com.fh0tracker.companion"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fh0tracker.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
