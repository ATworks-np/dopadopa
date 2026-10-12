package com.example.dopadopa.collector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Only our non-application overlays may be ignored; another foreground app still blocks capture. */
internal object ForegroundWindow {
    data class Window(val layer: Int, val type: Int, val packageName: String?, val active: Boolean, val focused: Boolean)

    fun select(windows: List<Window>, ownPackage: String): Int? {
        val next = windows.indices.sortedByDescending { windows[it].layer }.firstOrNull { index ->
            val window = windows[index]
            !(window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && window.packageName == ownPackage) &&
                (window.type == AccessibilityWindowInfo.TYPE_APPLICATION || window.active || window.focused)
        } ?: return null
        return next.takeIf { windows[it].type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            windows[it].packageName == YouTubeAdapter.PACKAGE }
    }

    fun youtubeRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows.orEmpty()
        if (windows.isEmpty()) return service.rootInActiveWindow?.takeIf { it.packageName?.toString() == YouTubeAdapter.PACKAGE }
        val roots = windows.map { it.root }
        val descriptors = windows.mapIndexed { index, window ->
            Window(window.layer, window.type, roots[index]?.packageName?.toString(), window.isActive, window.isFocused)
        }
        return select(descriptors, service.packageName)?.let { roots[it] }
    }
}
