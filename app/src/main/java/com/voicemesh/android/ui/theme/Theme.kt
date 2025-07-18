package com.voicemesh.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// VoiceMesh color palette - Terminal inspired dark theme
private val VoiceMeshDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00FF41),        // Matrix green
    onPrimary = Color(0xFF000000),      // Black
    primaryContainer = Color(0xFF003300), // Dark green
    onPrimaryContainer = Color(0xFF00FF41), // Matrix green
    
    secondary = Color(0xFF808080),      // Gray
    onSecondary = Color(0xFF000000),    // Black
    secondaryContainer = Color(0xFF333333), // Dark gray
    onSecondaryContainer = Color(0xFFFFFFFF), // White
    
    tertiary = Color(0xFF0099FF),       // Cyan accent
    onTertiary = Color(0xFF000000),     // Black
    tertiaryContainer = Color(0xFF003366), // Dark blue
    onTertiaryContainer = Color(0xFF0099FF), // Cyan
    
    error = Color(0xFFFF3333),          // Red
    onError = Color(0xFF000000),        // Black
    errorContainer = Color(0xFF660000), // Dark red
    onErrorContainer = Color(0xFFFF3333), // Red
    
    background = Color(0xFF000000),     // Pure black
    onBackground = Color(0xFF00FF41),   // Matrix green
    
    surface = Color(0xFF111111),        // Very dark gray
    onSurface = Color(0xFFFFFFFF),      // White
    surfaceVariant = Color(0xFF222222), // Dark gray
    onSurfaceVariant = Color(0xFF808080), // Gray
    
    outline = Color(0xFF404040),        // Medium gray
    outlineVariant = Color(0xFF303030), // Darker gray
    
    scrim = Color(0x80000000),          // Semi-transparent black
    
    inverseSurface = Color(0xFFFFFFFF), // White
    inverseOnSurface = Color(0xFF000000), // Black
    inversePrimary = Color(0xFF006600)  // Dark green
)

// Light theme (fallback, but VoiceMesh is primarily dark)
private val VoiceMeshLightColorScheme = lightColorScheme(
    primary = Color(0xFF006600),        // Dark green
    onPrimary = Color(0xFFFFFFFF),      // White
    primaryContainer = Color(0xFF80FF80), // Light green
    onPrimaryContainer = Color(0xFF000000), // Black
    
    secondary = Color(0xFF606060),      // Dark gray
    onSecondary = Color(0xFFFFFFFF),    // White
    secondaryContainer = Color(0xFFE0E0E0), // Light gray
    onSecondaryContainer = Color(0xFF000000), // Black
    
    tertiary = Color(0xFF0066CC),       // Dark blue
    onTertiary = Color(0xFFFFFFFF),     // White
    tertiaryContainer = Color(0xFF80CCFF), // Light blue
    onTertiaryContainer = Color(0xFF000000), // Black
    
    error = Color(0xFFCC0000),          // Dark red
    onError = Color(0xFFFFFFFF),        // White
    errorContainer = Color(0xFFFFCCCC), // Light red
    onErrorContainer = Color(0xFF000000), // Black
    
    background = Color(0xFFFFFFFE),     // Off-white
    onBackground = Color(0xFF000000),   // Black
    
    surface = Color(0xFFFFFFFE),        // Off-white
    onSurface = Color(0xFF000000),      // Black
    surfaceVariant = Color(0xFFF0F0F0), // Light gray
    onSurfaceVariant = Color(0xFF404040), // Dark gray
    
    outline = Color(0xFF808080),        // Gray
    outlineVariant = Color(0xFFC0C0C0), // Light gray
    
    scrim = Color(0x80000000),          // Semi-transparent black
    
    inverseSurface = Color(0xFF000000), // Black
    inverseOnSurface = Color(0xFFFFFFFF), // White
    inversePrimary = Color(0xFF00FF41)  // Matrix green
)

@Composable
fun VoiceMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> VoiceMeshDarkColorScheme
        else -> VoiceMeshLightColorScheme
    }
    
    // Force dark theme for VoiceMesh
    val finalColorScheme = VoiceMeshDarkColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = finalColorScheme.background.toArgb()
            window.navigationBarColor = finalColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = VoiceMeshTypography,
        content = content
    )
} 