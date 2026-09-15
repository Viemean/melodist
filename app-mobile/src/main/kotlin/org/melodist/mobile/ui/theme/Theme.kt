package org.melodist.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = MelodistPrimary,
        onPrimary = MelodistOnPrimary,
        primaryContainer = MelodistPrimaryContainer,
        onPrimaryContainer = MelodistOnPrimaryContainer,
        secondary = MelodistSecondary,
        onSecondary = MelodistOnSecondary,
        secondaryContainer = MelodistSecondaryContainer,
        onSecondaryContainer = MelodistOnSecondaryContainer,
        background = MelodistBackground,
        onBackground = MelodistOnBackground,
        surface = MelodistSurface,
        onSurface = MelodistOnSurface,
        surfaceVariant = MelodistSurfaceVariant,
        onSurfaceVariant = MelodistOnSurfaceVariant,
        surfaceContainer = MelodistSurfaceContainer,
        surfaceContainerHigh = MelodistSurfaceContainerHigh,
        error = MelodistError,
        onError = MelodistOnError,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = MelodistPrimaryContainer,
        onPrimary = MelodistOnPrimaryContainer,
        primaryContainer = MelodistPrimary,
        onPrimaryContainer = MelodistOnPrimary,
        secondary = MelodistSecondaryContainer,
        onSecondary = MelodistOnSecondaryContainer,
    )

@Composable
fun MelodistMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
