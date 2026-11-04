plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val quoteAssets = layout.buildDirectory.dir("generated/quote-assets")
val copyQuotes by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("../docs/quotes.json"))
    into(quoteAssets)
}
tasks.named("preBuild") { dependsOn(copyQuotes) }

android {
    namespace = "io.github.justcallmesu.aristotle"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.justcallmesu.aristotle"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // quotes.json has one source, docs/quotes.json: copied into a generated assets dir at build time (TQ1).
    sourceSets.getByName("main").assets.srcDir(quoteAssets.get().asFile)

    buildFeatures {
        compose = true
        // TS1: About shows the version from BuildConfig.
        buildConfig = true
    }

    // T19b: the terminal streams a PC shell; it never starts a process on the phone. Termux's JNI library (pty and
    // fork for a local shell) is left out of the APK, so TerminalSession's local-process path cannot run at all.
    packaging {
        jniLibs.excludes += "**/libtermux.so"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.play.services.code.scanner)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.okhttp.mockwebserver3)
}
