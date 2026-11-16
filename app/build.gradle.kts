plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val testRuntime = rootProject.file(providers.environmentVariable("KOTIKI_RUNTIME_DIR").orNull ?: ".local")
android {
    namespace = "team.kotiki.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "team.kotiki.app.demo"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-demo"
    }
    buildFeatures { compose = true }
    // Optional local aliases for updating installations from private prototypes.
    providers.environmentVariable("KOTIKI_MIGRATION_ASSETS")?.orNull?.let {
        sourceSets.getByName("main").assets.srcDir(it)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("user.home", testRuntime.resolve("test-home").absolutePath)
            it.systemProperty("java.io.tmpdir", testRuntime.resolve("test-temp").absolutePath)
            it.maxHeapSize = "2g"
            it.doFirst {
                testRuntime.resolve("test-home").mkdirs()
                testRuntime.resolve("test-temp").mkdirs()
            }
        }
    }
    buildTypes {
        debug {
            // Optional existing demo key. Otherwise AGP creates a local debug key.
            System.getenv("KOTIKI_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { keyPath ->
                fun requiredEnv(name: String) = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
                    "$name must be set when KOTIKI_DEBUG_KEYSTORE is configured"
                }
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = file(keyPath)
                    storePassword = requiredEnv("KOTIKI_DEBUG_STORE_PASSWORD")
                    keyAlias = requiredEnv("KOTIKI_DEBUG_KEY_ALIAS")
                    keyPassword = requiredEnv("KOTIKI_DEBUG_KEY_PASSWORD")
                }
            }
        }
        release { isMinifyEnabled = false }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
