package moe.antimony.hoshi.features.update

/**
 * Compile-time configuration for the GitHub-release auto-updater.
 *
 * This fork ships with the updater **disabled** ([AUTO_UPDATE_ENABLED] = `false`): on launch
 * the app does not start the periodic update-check `WorkManager` job and clears any job a
 * previous (enabled) build may have persisted, the "Update Downloaded — install now?"
 * prompt is not mounted, and the "Automatically Download Updates" toggle is hidden from
 * Reader → Behavior. No network request is ever made by the updater while the flag is off.
 *
 * Flip [AUTO_UPDATE_ENABLED] to `true` (and point [GITHUB_OWNER]/[GITHUB_REPO] at your own
 * fork) to bring the updater back online; the per-user `autoDownloadUpdates` setting in
 * [UpdateSettings] then decides whether checks actually run.
 */
internal object UpdateConfig {
    /**
     * Master switch for the auto-updater. `false` = offline (dormant, no network calls);
     * `true` = the original GitHub-release check / download / install-prompt path runs.
     *
     * Fork owners flip this here, rebuild, and ship.
     */
    const val AUTO_UPDATE_ENABLED: Boolean = true

    /**
     * The GitHub `<owner>/<repo>` whose releases the updater polls. Change these to point
     * at your fork's releases. The repo must publish releases with a `.apk` asset, matching
     * upstream's release layout (see [GitHubReleaseUpdateRepository] for the parser).
     */
    const val GITHUB_OWNER: String = "MKLOL"
    const val GITHUB_REPO: String = "Hoshi-Reader-Android"

    /** Human-facing repository URL used by the About screen's "Source" link. */
    const val REPO_URL: String = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /** GitHub Releases API endpoint for the latest release of [GITHUB_OWNER]/[GITHUB_REPO]. */
    const val LATEST_RELEASE_API_URL: String =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
}
