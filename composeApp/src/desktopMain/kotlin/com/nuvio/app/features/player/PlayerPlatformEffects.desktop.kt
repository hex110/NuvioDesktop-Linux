package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.features.player.desktop.DesktopScreenAwake
import com.nuvio.app.features.player.desktop.setDesktopPictureInPicture
import com.nuvio.app.features.player.desktop.desktopPictureInPictureState

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    // NUVIO-LINUX: the fork stubs this out, so nothing stops the compositor
    // blanking and locking the screen mid-film. See DesktopScreenAwake.
    DisposableEffect(keepScreenAwake) {
        DesktopScreenAwake.setEnabled(keepScreenAwake)
        onDispose { DesktopScreenAwake.setEnabled(false) }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    isPlaying: Boolean,
    playerSize: IntSize,
) {
    DisposableEffect(isActive) {
        setDesktopPictureInPicture(isActive)
        onDispose {
            if (isActive) setDesktopPictureInPicture(false)
        }
    }
    val platformActive = desktopPictureInPictureState.value
    LaunchedEffect(platformActive) {
        if (platformActive != isActive) onActiveChange(platformActive)
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? = null
