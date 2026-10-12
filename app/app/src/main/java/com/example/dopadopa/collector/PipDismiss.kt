package com.example.dopadopa.collector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Decides which controls stop playback or close a picture-in-picture window. */
object PipControls {
    private val pauseLabels = setOf("一時停止", "動画を一時停止", "Pause", "Pause video")

    fun isPauseAction(description: CharSequence?) = description?.toString()?.trim() in pauseLabels

    fun isPauseControl(className: String?, viewId: String?, description: CharSequence?): Boolean {
        if (!isPauseAction(description)) return false
        val kind = className.orEmpty()
        val id = viewId.orEmpty()
        return id.contains("play_pause") || kind.endsWith("Button") || kind.endsWith("ImageView")
    }

    fun isSystemClose(packageName: String?, viewId: String?, description: CharSequence?): Boolean {
        val pkg = packageName.orEmpty()
        if (pkg != "com.android.systemui" && !pkg.startsWith("com.android.wm.shell")) return false
        val id = viewId.orEmpty().substringAfterLast('/').lowercase()
        if (id == "dismiss" || id == "close" || id.contains("dismiss") || id.endsWith("_close")) return true
        return description?.toString()?.trim() in setOf("閉じる", "Close", "ピクチャー イン ピクチャーを閉じる")
    }
}

/** Pauses the YouTube player when its pause control is already in the tree. */
object YouTubePause {
    private const val BUDGET = 400

    fun clickPause(root: AccessibilityNodeInfo?): Boolean {
        if (root?.packageName?.toString() != YouTubeAdapter.PACKAGE) return false
        return clickPause(root, intArrayOf(BUDGET))
    }

    fun revealControls(root: AccessibilityNodeInfo?): Boolean {
        if (root?.packageName?.toString() != YouTubeAdapter.PACKAGE) return false
        val nodes = root.findAccessibilityNodeInfosByViewId("${YouTubeAdapter.PACKAGE}:id/player_overlays")
        try {
            val target = nodes.firstOrNull() ?: return false
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun clickPause(node: AccessibilityNodeInfo, budget: IntArray): Boolean {
        if (budget[0]-- <= 0) return false
        if (PipControls.isPauseControl(node.className?.toString(), node.viewIdResourceName, node.contentDescription) &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (clickPause(child, budget)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}

/** Closes a picture-in-picture window created when leaving YouTube. */
object PipDismiss {
    private const val BUDGET = 300

    data class Result(val closed: Boolean, val revealed: Boolean)

    fun close(service: AccessibilityService, reveal: Boolean): Result {
        val windows = service.windows ?: return Result(false, false)
        var closed = false
        var revealed = false
        try {
            val pip = windows.filter { it.isInPictureInPictureMode }
            if (pip.isEmpty()) return Result(false, false)
            for (window in windows) {
                val root = window.root ?: continue
                try {
                    if (window.isInPictureInPictureMode) {
                        closed = dismiss(root, pipWindow = true, intArrayOf(BUDGET)) || closed
                        if (!closed && reveal && !revealed && root.performAction(AccessibilityNodeInfo.ACTION_CLICK)) revealed = true
                    } else if (!closed) {
                        closed = dismiss(root, pipWindow = false, intArrayOf(BUDGET)) || closed
                    }
                } finally {
                    root.recycle()
                }
            }
        } finally {
            windows.forEach(AccessibilityWindowInfo::recycle)
        }
        return Result(closed, revealed)
    }

    private fun dismiss(node: AccessibilityNodeInfo, pipWindow: Boolean, budget: IntArray): Boolean {
        if (budget[0]-- <= 0) return false
        if (pipWindow && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS } &&
            node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) return true
        if (PipControls.isSystemClose(node.packageName?.toString(), node.viewIdResourceName, node.contentDescription) &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (dismiss(child, pipWindow, budget)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
