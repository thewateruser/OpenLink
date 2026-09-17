package com.openlink.child.enforcement

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.openlink.child.MainActivity

/**
 * Draws (and tears down) the full-screen, non-dismissible blocking overlay.
 *
 * Built with plain Android views rather than a Compose UI: hosting a ComposeView needs a
 * ViewTreeLifecycleOwner / SavedStateRegistryOwner, which a bare Service or AccessibilityService
 * doesn't provide out of the box. Plain views keep this self-contained and dependency-free.
 *
 * The window type is passed in rather than hard-coded: [PolicyForegroundAccessibilityService]
 * uses `TYPE_ACCESSIBILITY_OVERLAY`, which its accessibility binding grants implicitly -- see the
 * design-note comment on that class.
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayType: Int
) {
    private var overlayView: View? = null

    fun show(packageName: String, reason: BlockReason) {
        val existing = overlayView
        if (existing != null) {
            existing.findViewById<TextView>(ID_MESSAGE)?.text = messageFor(packageName, reason)
            return
        }
        val root = buildOverlayView(packageName, reason)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            windowManager.addView(root, params)
            overlayView = root
            root.isFocusableInTouchMode = true
            root.requestFocus()
        } catch (e: Exception) {
            // Overlay type unavailable in this window/permission state. The next foreground-app
            // change (or policy refresh) will retry.
        }
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already removed / window gone.
            }
        }
        overlayView = null
    }

    fun isShowing(): Boolean = overlayView != null

    private fun buildOverlayView(packageName: String, reason: BlockReason): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 20, 20, 20))
            setPadding(64, 64, 64, 64)
            isClickable = true
            isFocusable = true
            // The one job this overlay must do per spec: it can't be dismissed by the back
            // button. Consuming KEYCODE_BACK here (the view is focusable and requests focus
            // right after being added) stops the key event from reaching whatever's underneath.
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        }

        val title = TextView(context).apply {
            text = "OpenLink"
            setTextColor(Color.WHITE)
            textSize = 22f
        }
        val message = TextView(context).apply {
            id = ID_MESSAGE
            text = messageFor(packageName, reason)
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(0, 32, 0, 48)
        }
        val requestButton = Button(context).apply {
            text = "Request more time"
            setOnClickListener {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_REQUEST_TIME_PACKAGE, packageName)
                }
                context.startActivity(intent)
            }
        }

        container.addView(title)
        container.addView(message)
        container.addView(requestButton)
        return container
    }

    private fun messageFor(packageName: String, reason: BlockReason): String = when (reason) {
        BlockReason.HARD_BLOCKED -> "$packageName is blocked by a parent."
        BlockReason.DOWNTIME -> "It's downtime right now. This app is unavailable."
        BlockReason.LIMIT_REACHED -> "$packageName's daily time limit has been reached."
    }

    companion object {
        // Arbitrary, stable ids used only within this view hierarchy (no R.id needed).
        private const val ID_MESSAGE = 1002
    }
}
