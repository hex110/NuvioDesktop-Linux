package com.nuvio.app.features.updater

/** How a downloaded update was applied, so the UI can message the user correctly. */
enum class UpdateInstallOutcome {
    /** Build staged; a helper swaps it in after the app exits, so the app is restarting now. */
    RESTARTING,

    /** The verified archive was revealed for the user to extract over their folder manually. */
    REVEALED,
}

/** Which GitHub release the in-app updater follows. */
enum class UpdateChannel {
    /** The newest published, non-prerelease release. */
    Stable,

    /** The rolling "Nightly" prerelease, re-uploaded under one tag. */
    Nightly,
}

/**
 * Identity of the nightly build currently installed.
 *
 * Every nightly ships under the same tag and the same version name, so neither can say whether a
 * given nightly is already installed. The release asset's checksum can, and [publishedAt] gives the
 * build a human-readable name for the About row and for bug reports.
 */
data class InstalledNightlyBuild(
    val id: String,
    val publishedAt: String? = null,
) {
    /** Short label for display: the upload date when known, else a checksum prefix. */
    val label: String get() = publishedAt?.take(10)?.takeIf { it.isNotBlank() } ?: id.take(7)
}

expect object AppUpdaterPlatform {
    val isSupported: Boolean

    /**
     * The Linux build. Owned by the system package manager, so the update is handed to it: in one
     * step when [canInstallLinuxPackageInApp], otherwise as a command to copy.
     */
    val isLinux: Boolean

    /** The release tag this package was built from (e.g. `v1.15.0-linux3`); null off Linux. */
    val installedLinuxRelease: String?

    /** Whether the update check runs on launch. Linux only; the Windows updater always checks. */
    fun isAutoCheckEnabled(): Boolean

    fun setAutoCheckEnabled(enabled: Boolean)

    /** Which release asset suits this system's package manager: pacman takes `.pkg.tar.zst`, the rest `.deb`. */
    fun preferredLinuxAssetSuffix(): String

    /** The shell command that installs the package at [assetUrl], for the package manager in use. */
    fun linuxInstallCommand(assetUrl: String, assetName: String): String

    /**
     * Whether this Linux install can update itself in one step: download the package, install it
     * behind the system's password prompt, restart. False when there is no way to ask for root
     * from the GUI; the dialog then hands over [linuxInstallCommand] instead.
     */
    val canInstallLinuxPackageInApp: Boolean

    /**
     * Installs the downloaded package at [path] as root (pkexec, else `sudo -A` with an askpass
     * helper), then schedules a restart into the new build. Fails without side effects when the
     * prompt is dismissed or the package manager refuses.
     */
    suspend fun installLinuxPackage(path: String): Result<UpdateInstallOutcome>

    fun getSupportedAbis(): List<String>

    /** The release channel the updater follows; defaults to [UpdateChannel.Stable]. */
    fun getUpdateChannel(): UpdateChannel

    fun setUpdateChannel(channel: UpdateChannel)

    /** The installed nightly build, or null when this install came from the stable channel. */
    fun getInstalledNightlyBuild(): InstalledNightlyBuild?

    fun setInstalledNightlyBuild(build: InstalledNightlyBuild?)

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    /** Epoch millis until which the automatic check stays quiet ("Remind me in…"), or null. */
    fun getSnoozedUntilMs(): Long?

    fun setSnoozedUntilMs(epochMs: Long?)

    /** Whether verified updates are applied in place (and the app restarted) automatically. */
    fun isInPlaceUpdateEnabled(): Boolean

    fun setInPlaceUpdateEnabled(enabled: Boolean)

    /**
     * How many bytes an in-place update of this install would actually download (only what changed), or null when it would take
     * the whole archive or that can't be worked out. Cheap: reads the zip's index, not the zip.
     */
    suspend fun estimateDownloadBytes(assetUrl: String): Long?

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<UpdateInstallOutcome>
}