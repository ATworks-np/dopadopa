package com.example.dopadopa.intervention

import android.content.Context
import android.content.res.Configuration

/** Colors for the suggestion card and the pause screen. Follows the system night mode. */
data class OverlayColors(
    val night: Boolean,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val surface: Int,
    val canvas: Int,
    val raised: Int,
    val well: Int,
    val line: Int,
    val outline: Int,
    val halo: Int,
    val track: Int
) {
    companion object {
        fun night(uiMode: Int) = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        fun of(uiMode: Int) = if (night(uiMode)) dark else light

        fun of(context: Context) = of(context.resources.configuration.uiMode)

        val light = OverlayColors(
            night = false,
            ink = 0xFF232734.toInt(),
            muted = 0xFF676D7E.toInt(),
            accent = 0xFF5364DB.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            surface = 0xFFFAFAFD.toInt(),
            canvas = 0xFFF6F7FC.toInt(),
            raised = 0xFFEDEFF6.toInt(),
            well = 0xFFEFF1F8.toInt(),
            line = 0xFFE2E5EF.toInt(),
            outline = 0xFFC6CCE0.toInt(),
            halo = 0xFFE5E9FF.toInt(),
            track = 0xFFD9DDED.toInt()
        )

        val dark = OverlayColors(
            night = true,
            ink = 0xFFE7E9F2.toInt(),
            muted = 0xFFB0B6C8.toInt(),
            accent = 0xFFA9B5FF.toInt(),
            onAccent = 0xFF16182A.toInt(),
            surface = 0xFF1A1D29.toInt(),
            canvas = 0xFF11131C.toInt(),
            raised = 0xFF262B3C.toInt(),
            well = 0xFF232838.toInt(),
            line = 0xFF3A4054.toInt(),
            outline = 0xFF5C647C.toInt(),
            halo = 0x38A9B5FF,
            track = 0xFF343A4E.toInt()
        )
    }
}
