plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}

val hasGitMetadata = rootProject.file(".git").exists()
val spotlessBaseSha =
    providers
        .environmentVariable("SPOTLESS_BASE_SHA")
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
val validCommitSha = Regex("[0-9a-fA-F]{40}")
val kotlinPathspecs = listOf("app/src", "benchmark/src")

fun gitPaths(arguments: List<String>): List<String> =
    providers
        .exec {
            commandLine(listOf("git") + arguments)
        }.standardOutput
        .asText
        .get()
        .split('\u0000')
        .filter(String::isNotEmpty)

val kotlinFileSets: Pair<List<String>, List<String>> =
    if (hasGitMetadata && spotlessBaseSha != null && validCommitSha.matches(spotlessBaseSha)) {
        val added =
            gitPaths(
                listOf("diff", "--name-only", "-z", "--diff-filter=A", spotlessBaseSha, "--") +
                    kotlinPathspecs,
            )
        val untracked =
            gitPaths(
                listOf("ls-files", "--others", "--exclude-standard", "-z", "--") +
                    kotlinPathspecs,
            )
        val newFiles =
            (added + untracked)
                .distinct()
                .filter { path -> path.endsWith(".kt") && rootProject.file(path).isFile }
                .sorted()
        val existingFiles =
            gitPaths(
                listOf("diff", "--name-only", "-z", "--diff-filter=CMR", spotlessBaseSha, "--") +
                    kotlinPathspecs,
            ).distinct()
                .filterNot(newFiles.toSet()::contains)
                .filter { path -> path.endsWith(".kt") && rootProject.file(path).isFile }
                .sorted()
        Pair(newFiles, existingFiles)
    } else {
        Pair(emptyList(), emptyList())
    }
val newKotlinFiles = kotlinFileSets.first
val changedExistingKotlinFiles = kotlinFileSets.second

val verifySpotlessBase by tasks.registering {
    group = "verification"
    description = "Fails Spotless source checks without a validated event-specific Git baseline."
    onlyIf { hasGitMetadata }
    doLast {
        check(spotlessBaseSha != null && validCommitSha.matches(spotlessBaseSha)) {
            "SPOTLESS_BASE_SHA debe ser un SHA-1 de 40 caracteres resuelto por scripts/resolve-spotless-base.sh"
        }
    }
}

spotless {
    // A real checkout receives an event-specific merge-base from resolve-spotless-base.sh.
    // New Kotlin is formatted completely; existing files get only no-churn whitespace rules.
    // Without .git metadata both Kotlin sets stay empty and the Kotlin comparison is skipped
    // outright: KTS, YAML and project text are still validated. That skip is declared, never
    // presented as a passing Kotlin check.
    if (newKotlinFiles.isNotEmpty()) {
        kotlin {
            target(newKotlinFiles)
            ktlint("1.8.0")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
    if (changedExistingKotlinFiles.isNotEmpty()) {
        format("kotlinChangedExisting") {
            target(changedExistingKotlinFiles)
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.8.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("projectText") {
        target(
            ".editorconfig",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "*.md",
            "docs/**/*.md",
            "*.json",
            "*.properties",
            "gradle/**/*.toml",
            "gradle/**/*.properties",
            "scripts/*.sh",
            "scripts/*.rb",
        )
        // Test evidence uses Markdown hard line breaks intentionally and is immutable.
        targetExclude(
            "**/build/**",
            "evidence/**",
            "docs/test-evidence/**",
            "local.properties",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("spotlessCheck") {
    dependsOn(verifySpotlessBase)
}
tasks.named("spotlessApply") {
    dependsOn(verifySpotlessBase)
}

tasks.register("ciStaticAnalysis") {
    group = "verification"
    description = "Runs deterministic formatting and the project's architectural static gates."
    dependsOn(
        "spotlessCheck",
        ":app:verifyDomainBoundaries",
        ":app:verifyUiConventions",
        ":app:verifyNoSensitiveLogging",
        ":app:verifyMobileSecurityBoundaries",
        ":app:verifyLocalOcrConfiguration",
        ":app:verifyOfflineFirstBoundaries",
        ":app:verifyRoomSchemaPolicy",
    )
}
