package com.lys.toupin.receiver.utils

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object DeviceUtils {

    @Composable
    fun detectLayoutType(): DeviceLayoutType {
        val configuration = LocalConfiguration.current
        val context = LocalContext.current
        val smallestWidthDp = configuration.smallestScreenWidthDp
        val orientation = configuration.orientation

        return when {
            isTv(context) -> DeviceLayoutType.TV_LANDSCAPE
            smallestWidthDp >= 600 -> DeviceLayoutType.TABLET_LANDSCAPE
            orientation == Configuration.ORIENTATION_LANDSCAPE -> DeviceLayoutType.PHONE_LANDSCAPE
            else -> DeviceLayoutType.PHONE_PORTRAIT
        }
    }

    private fun isTv(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun configureFullscreen(window: Window, enabled: Boolean) {
        if (enabled) {
            val flags = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            window.decorView.systemUiVisibility = flags
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}

val titleFontSize
    @Composable
    get() = when (DeviceUtils.detectLayoutType()) {
        DeviceLayoutType.TV_LANDSCAPE,
        DeviceLayoutType.TABLET_LANDSCAPE -> 32.sp

        DeviceLayoutType.PHONE_LANDSCAPE -> 22.sp
        DeviceLayoutType.PHONE_PORTRAIT -> 28.sp
    }

val bodyFontSize
    @Composable
    get() = when (DeviceUtils.detectLayoutType()) {
        DeviceLayoutType.TV_LANDSCAPE,
        DeviceLayoutType.TABLET_LANDSCAPE -> 18.sp

        DeviceLayoutType.PHONE_LANDSCAPE -> 14.sp
        DeviceLayoutType.PHONE_PORTRAIT -> 16.sp
    }

val horizontalPadding
    @Composable
    get() = when (DeviceUtils.detectLayoutType()) {
        DeviceLayoutType.TV_LANDSCAPE,
        DeviceLayoutType.TABLET_LANDSCAPE -> 64.dp

        DeviceLayoutType.PHONE_LANDSCAPE -> 32.dp
        DeviceLayoutType.PHONE_PORTRAIT -> 24.dp
    }

val verticalPadding
    @Composable
    get() = when (DeviceUtils.detectLayoutType()) {
        DeviceLayoutType.TV_LANDSCAPE,
        DeviceLayoutType.TABLET_LANDSCAPE -> 48.dp

        DeviceLayoutType.PHONE_LANDSCAPE -> 16.dp
        DeviceLayoutType.PHONE_PORTRAIT -> 32.dp
    }
