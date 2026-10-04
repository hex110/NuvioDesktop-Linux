package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.streams.ConnectionSpeedEstimator
import com.nuvio.app.features.streams.StreamConnectionFitPreference
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_learning
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_measured
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_title
import org.jetbrains.compose.resources.stringResource

/** "Match streams to connection", from Nuvio Reshaped's settings page. */
@Composable
internal fun ConnectionFitSettingsRow(isTablet: Boolean) {
    val enabled by remember {
        StreamConnectionFitPreference.ensureLoaded()
        StreamConnectionFitPreference.enabled
    }.collectAsStateWithLifecycle()
    val revision by ConnectionSpeedEstimator.revision.collectAsStateWithLifecycle()
    val connectionMbps = remember(revision) { ConnectionSpeedEstimator.estimateMbps() }
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_stream_connection_fit_title),
        description = if (connectionMbps == null) {
            stringResource(Res.string.settings_stream_connection_fit_learning)
        } else {
            stringResource(Res.string.settings_stream_connection_fit_measured, connectionMbps.roundToInt())
        },
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = StreamConnectionFitPreference::setEnabled,
    )
}
