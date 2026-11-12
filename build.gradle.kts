plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "in.bleuphoenix"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("androidStudioPath"))
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        description = providers.fileContents(layout.projectDirectory.file("docs/marketplace/description.html")).asText
        // Converted while configuring (a provider lambda calling a script function breaks the configuration cache);
        // Gradle still re-configures when CHANGELOG.md changes.
        changeNotes = changelogToHtml(layout.projectDirectory.file("CHANGELOG.md").asFile.readText(), versions = 4)
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

// Dev-only: renders the README / Marketplace screenshots in a sandbox IDE with the sample project open
// (see dev/ScreenshotRunner.kt). The Dart plugin comes from a local IDE install, e.g.
//   ./gradlew runIdeForScreenshots -PlocalIdePluginsDir="$HOME/Library/Application Support/Google/AndroidStudio2026.2/plugins"
// Output: build/screenshots/*.png
val localIdePluginsDir = providers.gradleProperty("localIdePluginsDir").orNull
intellijPlatformTesting {
    runIde {
        register("runIdeForScreenshots") {
            task {
                jvmArgs(
                    "-Dflutterpreview.screenshots=${layout.buildDirectory.dir("screenshots").get().asFile.path}",
                    "-Ddisable.android.first.run=true",
                    "-Didea.trust.all.projects=true",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Djb.privacy.policy.text=<!--999.999-->",
                    "-Dide.show.tips.on.startup.default.value=false",
                )
                args(layout.projectDirectory.dir("sample").asFile.path)
            }
        }
    }
}
// The Dart plugin (gutter icons, Generate Flutter Preview…) isn't bundled with Android Studio: copy it into that sandbox.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>()
    .matching { it.name == "prepareSandbox_runIdeForScreenshots" }
    .configureEach {
        localIdePluginsDir?.let { dir -> from(file("$dir/Dart")) { into("Dart") } }
    }

tasks.test {
    // Integration test: set FP_IT_PROJECT to a Flutter package with @Preview widgets to render them for real.
    environment("FP_IT_PROJECT", System.getenv("FP_IT_PROJECT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * The newest [versions] entries of CHANGELOG.md ("## [x.y.z] - date", "### Added", "- item") as HTML for the
 * plugin's change notes. Supports `code`, **bold** and [links](url) inside items.
 */
fun changelogToHtml(markdown: String, versions: Int): String {
    fun inline(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("""\*\*([^*]+)\*\*"""), "<b>$1</b>")
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "<a href=\"$2\">$1</a>")
    val html = StringBuilder()
    var count = 0
    var inList = false
    fun closeList() { if (inList) { html.append("</ul>\n"); inList = false } }
    for (line in markdown.lines()) {
        val version = Regex("""^## \[([^\]]+)](?: - (.+))?""").find(line)
        when {
            version != null -> {
                closeList()
                if (++count > versions) break
                val date = version.groupValues[2].takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                html.append("<h3>${version.groupValues[1]}$date</h3>\n")
            }
            count == 0 -> Unit
            line.startsWith("### ") -> { closeList(); html.append("<p><b>${inline(line.removePrefix("### "))}</b></p>\n") }
            line.startsWith("- ") -> {
                if (!inList) { html.append("<ul>\n"); inList = true }
                html.append("<li>${inline(line.removePrefix("- "))}</li>\n")
            }
        }
    }
    closeList()
    return html.toString() + "<p><a href=\"https://github.com/Jemshi101/FlutterPreview/blob/main/CHANGELOG.md\">Full changelog</a></p>"
}
