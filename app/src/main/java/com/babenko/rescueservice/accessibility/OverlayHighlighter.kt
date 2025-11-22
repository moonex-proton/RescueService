package com.babenko.rescueservice.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import com.babenko.rescueservice.core.Logger

class OverlayHighlighter(private val context: Context) { // <--- CHANGE HERE
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: HighlightView? = null

    // --- ADDED: auto-hide overlay management ---
    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null

    // --- ADDED: public API for M1 ---

    /**
     * Show rectangle highlighting on the screen.
     * @param rect screen coordinates of the rectangle (px)
     * @param autoDismissMs auto-hide after the specified time (ms). 0 or less - no auto-hide.
     */
    fun show(rect: Rect, autoDismissMs: Long = 3000L) {
        try {
            // === NEW: runtime check for overlay permission and soft transition to settings ===
            if (!Settings.canDrawOverlays(context)) {
                Logger.d("OverlayHighlighter: overlay permission missing -> opening settings")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            // ==============================================================================

            val existing = overlayView
            if (existing == null) {
                val view = HighlightView(context, Rect(rect))
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                windowManager.addView(view, lp)
                overlayView = view
                Logger.d("OverlayHighlighter: overlay added")
            } else {
                existing.updateRect(rect)
                existing.invalidate()
                Logger.d("OverlayHighlighter: overlay updated")
            }

            // Reset and start auto-hide
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            if (autoDismissMs > 0L) {
                autoHideRunnable = Runnable { hide() }
                handler.postDelayed(autoHideRunnable!!, autoDismissMs)
            } else {
                autoHideRunnable = null
            }
        } catch (e: Exception) {
            Logger.d("OverlayHighlighter.show error: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Hide the highlight if it exists.
     */
    fun hide() {
        try {
            autoHideRunnable?.let { handler.removeCallbacks(it) }
            autoHideRunnable = null
            overlayView?.let { v ->
                windowManager.removeView(v)
                overlayView = null
                Logger.d("OverlayHighlighter: overlay removed")
            }
        } catch (e: Exception) {
            Logger.d("OverlayHighlighter.hide error: ${e.javaClass.name}: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Below we leave the previously existing code, without changing its purpose.
    // -------------------------------------------------------------------------

    // ... (leave possible auxiliary members if they were above)

    private class HighlightView(context: Context, private var rect: Rect) : View(context) {
        private val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }

        fun updateRect(newRect: Rect) {
            this.rect.set(newRect)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(rect, paint)
        }
    }
}
