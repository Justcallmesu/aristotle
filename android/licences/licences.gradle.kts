// D45: third-party licence notices for the release APK. Gradle's own API only, no plugin.
//   ./gradlew :app:licencesWrite   regenerates src/main/assets/licences.json and src/main/assets/licences/*.txt
//   ./gradlew :app:licencesCheck   fails when the dependency set (or a text) changed and the assets are stale
// scripts/licences.sh wraps both and does the server side too. Inventory = releaseRuntimeClasspath (what ends up in the APK)
// plus the bundled fonts. The licence of each artifact comes from its POM; the few POMs that say nothing or "Other" are
// pinned below, with the reason. A licence this file does not know makes the task fail (no guessing).

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

data class LicenceComponent(
    val name: String,
    val version: String,
    val licence: String,
    val copyright: String,
    val notice: String?,
)

/** Licence id -> display name. The text of each lives in android/licences/text/<id>.txt. */
val licenceKinds: Map<String, String> = mapOf(
    "apache-2.0" to "Apache License 2.0",
    "ofl-1.1" to "SIL Open Font License 1.1",
    "mpl-2.0" to "Mozilla Public License 2.0",
    // Google terms, not open source: no full text is reproduced, the file points at the terms (see the RS2 report).
    "android-sdk-terms" to "Android Software Development Kit License (Google terms, not open source)",
    "mlkit-terms" to "ML Kit Terms of Service (Google terms, not open source)",
)

/** POM licence name -> id. */
fun licenceIdOf(pomName: String): String? = when {
    pomName.contains("apache", ignoreCase = true) && pomName.contains("2") -> "apache-2.0"
    pomName.equals("Android Software Development Kit License", ignoreCase = true) -> "android-sdk-terms"
    pomName.equals("ML Kit Terms of Service", ignoreCase = true) -> "mlkit-terms"
    else -> null
}

/** "group:artifact" prefixes whose POM has no usable licence. */
val licencePins: Map<String, String> = mapOf(
    // POM says "Other"; termux-app's LICENSE.md puts these two modules (jackpal's Android Terminal Emulator code) under
    // Apache-2.0 and the maintainers confirm it (termux-app issue #4257, discussion #4642; T19b review).
    "com.github.termux.termux-app:" to "apache-2.0",
    // Guava's ListenableFuture-only artifact: the POM lists no licence, it inherits Guava's (Apache-2.0).
    "com.google.guava:listenablefuture" to "apache-2.0",
)

/** Group prefix -> copyright holder (longest prefix wins). A group missing here makes the task fail. */
val copyrightHolders: Map<String, String> = mapOf(
    "androidx." to "The Android Open Source Project",
    "com.google.android." to "Google LLC",
    "com.google.firebase" to "Google LLC",
    "com.google.mlkit" to "Google LLC",
    "com.google.guava" to "The Guava Authors",
    "com.squareup.okhttp3" to "Square, Inc.",
    "com.squareup.okio" to "Square, Inc.",
    "javax.inject" to "The JSR-330 authors (javax.inject)",
    "org.jetbrains" to "JetBrains s.r.o. and Kotlin Programming Language contributors",
    "org.jspecify" to "The JSpecify Authors",
    "com.mikepenz" to "Mike Penz",
    "com.github.termux.termux-app" to "Termux contributors; derived from Android Terminal Emulator by Jack Palevich",
)

/** The bundled fonts (res/font): not Gradle dependencies, so listed here; copyright lines are the fonts' own name tables. */
val bundledFonts: List<LicenceComponent> = listOf(
    LicenceComponent(
        "Atkinson Hyperlegible Mono (font)", "2.001", "ofl-1.1",
        "Copyright 2020-2024 The Atkinson Hyperlegible Mono Project Authors (https://github.com/googlefonts/atkinson-hyperlegible-next-mono)", null,
    ),
    LicenceComponent(
        "Atkinson Hyperlegible Next (font)", "2.001", "ofl-1.1",
        "Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors (https://github.com/googlefonts/atkinson-hyperlegible-next)", null,
    ),
)

/**
 * Third-party data embedded in a dependency's artifact: invisible to the classpath inventory, so listed here by hand.
 * [artifact] is the "group:artifact" that carries the file, [entry] its path inside the jar/aar. The task fails when the
 * entry is gone (the notice would be stale), and when an artifact carries an `assets/` file that is not listed here.
 * Scan of the 110 releaseRuntimeClasspath artifacts (RS2b): this is the only third-party data. Not third-party:
 * `third_party_licenses.*` (Google's own index in the GMS/ML Kit/Firebase AARs), `inspector.jar` (AndroidX), `DebugProbesKt.bin`
 * (kotlinx-coroutines), `libtermux.so` (Termux code, Apache-2.0), `libandroidx.graphics.path.so` (AndroidX), res/ files (each library's own).
 */
class BundledData(val component: LicenceComponent, val artifact: String, val entry: String)

val bundledData: List<BundledData> = listOf(
    BundledData(
        LicenceComponent(
            "Public Suffix List (bundled by OkHttp)", "", "mpl-2.0",
            "Copyright Mozilla Foundation and Public Suffix List contributors. Source form: https://publicsuffix.org/list/", null,
        ),
        "com.squareup.okhttp3:okhttp-android", "assets/PublicSuffixDatabase.list",
    ),
)

/** Entries of [artifact] (a jar or an aar's outer zip) that live under assets/. */
fun assetEntriesOf(artifact: File): List<String> {
    if (!artifact.isFile || !(artifact.name.endsWith(".jar") || artifact.name.endsWith(".aar"))) return emptyList()
    ZipFile(artifact).use { zip ->
        return zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/") }.map { it.name }.toList()
    }
}

fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

fun childElements(parent: Element, tag: String): List<Element> =
    (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/** The licence names of a POM (its own `<licenses>`; none inherited). */
fun pomLicenceNames(pom: File): List<String> {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
    return childElements(root, "licenses").flatMap { childElements(it, "license") }
        .flatMap { childElements(it, "name") }.map { it.textContent.trim() }
}

fun holderOf(group: String): String =
    copyrightHolders.entries.filter { group.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        ?: throw GradleException("No copyright holder known for group $group: add it to copyrightHolders in licences/licences.gradle.kts")

/** A NOTICE file inside the artifact (Apache-2.0 §4(d)); null when it ships none. */
fun noticeOf(artifact: File): String? {
    if (!artifact.isFile || !(artifact.name.endsWith(".jar") || artifact.name.endsWith(".aar"))) return null
    ZipFile(artifact).use { zip ->
        val entry = zip.entries().asSequence().firstOrNull {
            !it.isDirectory && Regex("(?i)^(META-INF/)?NOTICE(\\.txt|\\.md)?$").matches(it.name)
        } ?: return null
        return zip.getInputStream(entry).bufferedReader().readText().trim().takeIf { it.isNotEmpty() }?.plus("\n")
    }
}

/** The whole generated tree: path under src/main/assets -> content. */
fun buildLicenceFiles(): Map<String, String> {
    val artifacts = project.configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
    val ids = artifacts.map { it.id.componentIdentifier as ModuleComponentIdentifier }.distinct()
    val poms = HashMap<String, File>()
    val query = project.dependencies.createArtifactResolutionQuery().forComponents(ids)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
    for (component in query.resolvedComponents) {
        val id = component.id as ModuleComponentIdentifier
        val pom = component.getArtifacts(MavenPomArtifact::class.java).filterIsInstance<ResolvedArtifactResult>().firstOrNull()
            ?: throw GradleException("No POM for ${id.group}:${id.module}:${id.version}")
        poms["${id.group}:${id.module}:${id.version}"] = pom.file
    }
    val files = sortedMapOf<String, String>()
    val components = sortedMapOf<String, LicenceComponent>()
    val seenData = mutableSetOf<Pair<String, String>>()
    for (artifact in artifacts) {
        val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
        val coordinate = "${id.group}:${id.module}"
        val key = "$coordinate:${id.version}"
        if (key in components) continue
        val pinned = licencePins.entries.firstOrNull { coordinate.startsWith(it.key) }?.value
        val fromPom = pomLicenceNames(poms[key] ?: throw GradleException("No POM for $key")).map { licenceIdOf(it) ?: "?${it}" }.distinct()
        val licence = pinned ?: fromPom.singleOrNull()?.takeUnless { it.startsWith("?") }
            ?: throw GradleException("Unknown or ambiguous licence for $key: $fromPom (pin it in licencePins if it is open source)")
        val notice = noticeOf(artifact.file)?.let { text ->
            val noticeKey = "notice-" + sha256Hex(text).take(12)
            files["licences/$noticeKey.txt"] = text
            noticeKey
        }
        components[key] = LicenceComponent(coordinate, id.version, licence, "Copyright " + holderOf(id.group), notice)
        val known = bundledData.filter { it.artifact == coordinate }.map { it.entry }
        val unlisted = assetEntriesOf(artifact.file).filter { it !in known }
        if (unlisted.isNotEmpty()) {
            throw GradleException("$key bundles assets/ files with no licence entry: $unlisted (add them to bundledData in licences/licences.gradle.kts, or say why they are not third-party)")
        }
        seenData += known.filter { it in assetEntriesOf(artifact.file) }.map { coordinate to it }
    }
    for (data in bundledData) {
        if ((data.artifact to data.entry) !in seenData) {
            throw GradleException("${data.component.name}: ${data.artifact} no longer ships ${data.entry} (drop or fix its bundledData entry in licences/licences.gradle.kts)")
        }
        components["${data.component.name}:${data.component.version}"] = data.component
    }
    for (font in bundledFonts) components["${font.name}:${font.version}"] = font
    val list = components.values.sortedWith(compareBy({ it.name.lowercase() }, { it.version }))
    val used = list.map { it.licence }.distinct().sorted()
    val textDir = rootProject.layout.projectDirectory.dir("licences/text").asFile
    for (id in used) {
        val source = File(textDir, "$id.txt")
        if (!source.isFile) throw GradleException("Missing licence text ${source.path}")
        files["licences/$id.txt"] = source.readText()
    }
    files["licences.json"] = buildString {
        append("{\n  \"schema\": 1,\n  \"licences\": [\n")
        append(used.joinToString(",\n") { id ->
            "    {\"id\": ${jsonString(id)}, \"name\": ${jsonString(licenceKinds.getValue(id))}, \"text\": ${jsonString("licences/$id.txt")}}"
        })
        append("\n  ],\n  \"components\": [\n")
        append(list.joinToString(",\n") { c ->
            "    {\"name\": ${jsonString(c.name)}, \"version\": ${jsonString(c.version)}, \"licence\": ${jsonString(c.licence)}, " +
                "\"text\": ${jsonString("licences/${c.licence}.txt")}, \"copyright\": ${jsonString(c.copyright)}" +
                (c.notice?.let { ", \"notice\": ${jsonString("licences/$it.txt")}" } ?: "") + "}"
        })
        append("\n  ]\n}\n")
    }
    return files
}

val licenceAssetsDir: File = layout.projectDirectory.dir("src/main/assets").asFile

tasks.register("licencesWrite") {
    group = "licences"
    description = "Regenerates the licence assets from releaseRuntimeClasspath."
    doLast {
        val files = buildLicenceFiles()
        File(licenceAssetsDir, "licences").deleteRecursively()
        files.forEach { (path, text) -> File(licenceAssetsDir, path).apply { parentFile.mkdirs() }.writeText(text) }
        println("licences: wrote ${files.size} files (${files.getValue("licences.json").lines().count { it.startsWith("    {\"name\"") }} components)")
    }
}

tasks.register("licencesCheck") {
    group = "licences"
    description = "Fails when the committed licence assets differ from what releaseRuntimeClasspath needs."
    doLast {
        val files = buildLicenceFiles()
        val problems = mutableListOf<String>()
        files.forEach { (path, text) ->
            val onDisk = File(licenceAssetsDir, path)
            if (!onDisk.isFile) problems += "missing: $path" else if (onDisk.readText() != text) problems += "stale: $path"
        }
        File(licenceAssetsDir, "licences").walkTopDown().filter { it.isFile }.forEach {
            val rel = "licences/" + it.relativeTo(File(licenceAssetsDir, "licences")).path
            if (rel !in files) problems += "extra: $rel"
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Licence assets are stale (run scripts/licences.sh):\n  " + problems.joinToString("\n  "))
        }
        println("licences: assets up to date (${files.size} files)")
    }
}
