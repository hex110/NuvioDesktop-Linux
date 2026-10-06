package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxReleaseUpdateTest {
    private fun offered(remote: String, local: String) = UpdateAvailability.isOffered(
        update = AppUpdate(
            channel = UpdateChannel.Stable,
            tag = remote,
            title = remote,
            notes = "",
            releaseUrl = null,
            assetName = "",
            assetUrl = "",
            assetSizeBytes = null,
        ),
        localVersion = local,
        installedNightlyId = null,
    )

    @Test
    fun `a later linux revision of the same version is an update`() {
        assertTrue(offered("v1.15.0-linux3", "v1.15.0-linux2"))
        assertTrue(offered("v1.15.0-linux10", "v1.15.0-linux9"))
    }

    @Test
    fun `the installed revision and older ones are not offered`() {
        assertFalse(offered("v1.15.0-linux3", "v1.15.0-linux3"))
        assertFalse(offered("v1.15.0-linux2", "v1.15.0-linux3"))
    }

    @Test
    fun `a newer upstream version wins over a higher revision`() {
        assertTrue(offered("v1.16.0-linux1", "v1.15.0-linux7"))
        assertFalse(offered("v1.15.0-linux7", "v1.16.0-linux1"))
    }

    @Test
    fun `picks the package matching the package manager`() {
        val assets = listOf(
            GitHubAssetDto("Nuvio-HTPC-Linux-x86_64-v1.deb", "https://x/deb"),
            GitHubAssetDto("nuvio-htpc-bin-1-x86_64.pkg.tar.zst", "https://x/pkg"),
            GitHubAssetDto("PKGBUILD", "https://x/pkgbuild"),
        )
        assertEquals("https://x/pkg", selectLinuxPackageAsset(assets, ".pkg.tar.zst")?.browserDownloadUrl)
        assertEquals("https://x/deb", selectLinuxPackageAsset(assets, ".deb")?.browserDownloadUrl)
        assertNull(selectLinuxPackageAsset(assets.take(1), ".pkg.tar.zst"))
    }
}
