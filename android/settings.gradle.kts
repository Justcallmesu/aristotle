pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // T19b + D45: Termux terminal-emulator + terminal-view (Apache-2.0) are published only on JitPack. exclusiveContent
        // makes JitPack the only place this group is looked up (google()/mavenCentral() are never asked for it) and the
        // only group JitPack is asked for. The AARs are pinned by gradle/verification-metadata.xml.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "Aristotle"
include(":app")
