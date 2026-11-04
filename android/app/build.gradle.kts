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

// TV2: the app's "What's new" is CHANGELOG.md (the one source) cut down at build time to its `Phone:` entries.
// Server entries, `[Unreleased]` and sections left empty are dropped; `## [1.0.0] - date` becomes `## 1.0.0 (date)`.
val changelogAssets = layout.buildDirectory.dir("generated/changelog-assets")
val generateChangelog by tasks.registering {
    val source = rootProject.layout.projectDirectory.file("../CHANGELOG.md")
    val target = changelogAssets.map { it.file("changelog.md") }
    inputs.file(source)
    outputs.file(target)
    doLast {
        val release = Regex("""^## \[(.+?)\](?: - (.+))?$""")
        val out = mutableListOf<String>()
        var releaseHead: String? = null
        var sectionHead: String? = null
        var inRelease = false
        for (line in source.asFile.readLines()) {
            val m = release.matchEntire(line.trimEnd())
            when {
                m != null -> {
                    inRelease = !m.groupValues[1].equals("Unreleased", ignoreCase = true)
                    sectionHead = null
                    releaseHead = if (inRelease) "## " + m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") else null
                }
                !inRelease -> Unit
                line.startsWith("### ") -> sectionHead = line.trimEnd()
                line.startsWith("- Server:") -> Unit
                line.startsWith("- Phone:") || (line.isNotBlank() && !line.startsWith("-")) -> {
                    releaseHead?.let { out += listOf("", it, ""); releaseHead = null }
                    sectionHead?.let { out += listOf(it, ""); sectionHead = null }
                    out += if (line.startsWith("- Phone:")) "- " + line.removePrefix("- Phone:").trim() else line.trimEnd()
                }
                line.isBlank() -> if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
            }
        }
        val text = out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (!text.contains("\n- ")) throw GradleException("CHANGELOG.md has no released `Phone:` entries")
        target.get().asFile.apply { parentFile.mkdirs() }.writeText(text + "\n")
    }
}
tasks.named("preBuild") { dependsOn(generateChangelog) }

android {
    namespace = "io.github.justcallmesu.aristotle"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.justcallmesu.aristotle"
        minSdk = 29
        targetSdk = 36
        // versionCode = major*10000 + minor*100 + patch (1.0.0 -> 10000); the PC server bumps its own semver.
        versionCode = 10000
        versionName = "1.0.0"
    }

    // quotes.json has one source, docs/quotes.json: copied into a generated assets dir at build time (TQ1).
    sourceSets.getByName("main").assets.srcDir(quoteAssets.get().asFile)
    sourceSets.getByName("main").assets.srcDir(changelogAssets.get().asFile)

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
