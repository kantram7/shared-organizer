plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

// Optional external runtime directory used by build-local.ps1.
val externalRuntime = providers.environmentVariable("KOTIKI_RUNTIME_DIR").orNull
if (!externalRuntime.isNullOrBlank()) {
    allprojects {
        val modulePath = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
        layout.buildDirectory.set(file("$externalRuntime/build/$modulePath"))
    }
}
