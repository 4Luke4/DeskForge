package com.deskforge.app.ui

import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.deskforge.app.R
import com.deskforge.app.model.DesktopViewport
import kotlin.math.roundToInt

data class DesktopSurfaceCallbacks(
    val onSurfaceReady: (Surface, DesktopViewport) -> Unit,
    val onSurfaceResized: (DesktopViewport) -> Unit,
    val onSurfaceDestroyed: () -> Unit,
    val onPointer: (x: Int, y: Int, buttons: Int) -> Unit,
    val onKey: (keysym: Int, pressed: Boolean) -> Unit,
)

/** Focused Android boundary that converts tablet and physical input into bounded RFB events. */
class DesktopSurface(
    context: Context,
    var callbacks: DesktopSurfaceCallbacks,
) : SurfaceView(context), SurfaceHolder.Callback {
    private val heldKeysyms = mutableSetOf<Int>()
    private var pointerButtons = 0
    private var dragging = false
    private var verticalScrollRemainder = 0f
    private var horizontalScrollRemainder = 0f
    private val gestureDetector = GestureDetector(context, DesktopGestureListener())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(18, 22, 27))
        contentDescription = context.getString(R.string.desktop_preview)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (width > 0 && height > 0) callbacks.onSurfaceReady(holder.surface, viewport())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width >= MIN_WIDTH && height >= MIN_HEIGHT) {
            callbacks.onSurfaceReady(holder.surface, viewport(width, height))
            callbacks.onSurfaceResized(viewport(width, height))
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseInput()
        callbacks.onSurfaceDestroyed()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val pointerSource = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        if (!pointerSource) return super.onGenericMotionEvent(event)
        requestFocus()
        val x = event.x.toDesktopX()
        val y = event.y.toDesktopY()
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            sendWheel(x, y, event.getAxisValue(MotionEvent.AXIS_VSCROLL), vertical = true)
            sendWheel(x, y, event.getAxisValue(MotionEvent.AXIS_HSCROLL), vertical = false)
            return true
        }
        pointerButtons = event.buttonState.toRfbButtons()
        callbacks.onPointer(x, y, pointerButtons)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging || pointerButtons != 0) {
                    pointerButtons = 0
                    callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), 0)
                }
                dragging = false
                verticalScrollRemainder = 0f
                horizontalScrollRemainder = 0f
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = AndroidKeysym.from(event) ?: return super.onKeyDown(keyCode, event)
        callbacks.onKey(keysym, true)
        heldKeysyms += keysym
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val keysym = AndroidKeysym.from(event) ?: return super.onKeyUp(keyCode, event)
        callbacks.onKey(keysym, false)
        heldKeysyms -= keysym
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseInput()
    }

    private fun releaseInput() {
        if (pointerButtons != 0) callbacks.onPointer(0, 0, 0)
        pointerButtons = 0
        heldKeysyms.toList().forEach { keysym -> callbacks.onKey(keysym, false) }
        heldKeysyms.clear()
    }

    private fun viewport(width: Int = this.width, height: Int = this.height) = DesktopViewport(
        widthPx = width.coerceIn(MIN_WIDTH, MAX_DIMENSION),
        heightPx = height.coerceIn(MIN_HEIGHT, MAX_DIMENSION),
        densityDpi = resources.displayMetrics.densityDpi.coerceIn(120, 640),
    )

    private fun sendWheel(x: Int, y: Int, amount: Float, vertical: Boolean) {
        if (amount == 0f) return
        val steps = amount.roundToInt().coerceIn(-8, 8)
        repeat(kotlin.math.abs(steps)) {
            val wheelButton = if (vertical) {
                if (steps > 0) RFB_SCROLL_UP else RFB_SCROLL_DOWN
            } else {
                if (steps > 0) RFB_SCROLL_LEFT else RFB_SCROLL_RIGHT
            }
            callbacks.onPointer(x, y, pointerButtons or wheelButton)
            callbacks.onPointer(x, y, pointerButtons)
        }
    }

    private inner class DesktopGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean {
            callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), 0)
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            performClick()
            callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), RFB_PRIMARY)
            callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), 0)
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            if (dragging) return
            callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), RFB_SECONDARY)
            callbacks.onPointer(event.x.toDesktopX(), event.y.toDesktopY(), 0)
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            if (current.pointerCount >= 2) {
                if (dragging) {
                    dragging = false
                    pointerButtons = 0
                    callbacks.onPointer(current.x.toDesktopX(), current.y.toDesktopY(), 0)
                }
                verticalScrollRemainder += distanceY
                horizontalScrollRemainder += distanceX
                while (kotlin.math.abs(verticalScrollRemainder) >= SCROLL_THRESHOLD_PX) {
                    sendWheel(
                        current.x.toDesktopX(),
                        current.y.toDesktopY(),
                        if (verticalScrollRemainder < 0) 1f else -1f,
                        vertical = true,
                    )
                    verticalScrollRemainder += if (verticalScrollRemainder < 0) SCROLL_THRESHOLD_PX else -SCROLL_THRESHOLD_PX
                }
                while (kotlin.math.abs(horizontalScrollRemainder) >= SCROLL_THRESHOLD_PX) {
                    sendWheel(
                        current.x.toDesktopX(),
                        current.y.toDesktopY(),
                        if (horizontalScrollRemainder < 0) 1f else -1f,
                        vertical = false,
                    )
                    horizontalScrollRemainder += if (horizontalScrollRemainder < 0) SCROLL_THRESHOLD_PX else -SCROLL_THRESHOLD_PX
                }
                return true
            }
            dragging = true
            pointerButtons = RFB_PRIMARY
            callbacks.onPointer(current.x.toDesktopX(), current.y.toDesktopY(), pointerButtons)
            return true
        }
    }

    private fun Float.toDesktopX() = roundToInt().coerceIn(0, (width - 1).coerceAtLeast(0))
    private fun Float.toDesktopY() = roundToInt().coerceIn(0, (height - 1).coerceAtLeast(0))

    private fun Int.toRfbButtons(): Int {
        var buttons = 0
        if (this and MotionEvent.BUTTON_PRIMARY != 0) buttons = buttons or RFB_PRIMARY
        if (this and MotionEvent.BUTTON_TERTIARY != 0) buttons = buttons or RFB_MIDDLE
        if (this and MotionEvent.BUTTON_SECONDARY != 0) buttons = buttons or RFB_SECONDARY
        if (this and MotionEvent.BUTTON_BACK != 0) buttons = buttons or RFB_BACK
        return buttons
    }

    private companion object {
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 480
        const val MAX_DIMENSION = 4096
        const val SCROLL_THRESHOLD_PX = 48f
        const val RFB_PRIMARY = 1
        const val RFB_MIDDLE = 2
        const val RFB_SECONDARY = 4
        const val RFB_SCROLL_UP = 8
        const val RFB_SCROLL_DOWN = 16
        const val RFB_SCROLL_LEFT = 32
        const val RFB_SCROLL_RIGHT = 64
        const val RFB_BACK = 128
    }
}

internal object AndroidKeysym {
    private val keysyms = mapOf(
        KeyEvent.KEYCODE_DEL to 0xff08,
        KeyEvent.KEYCODE_TAB to 0xff09,
        KeyEvent.KEYCODE_ENTER to 0xff0d,
        KeyEvent.KEYCODE_ESCAPE to 0xff1b,
        KeyEvent.KEYCODE_FORWARD_DEL to 0xffff,
        KeyEvent.KEYCODE_INSERT to 0xff63,
        KeyEvent.KEYCODE_MOVE_HOME to 0xff50,
        KeyEvent.KEYCODE_MOVE_END to 0xff57,
        KeyEvent.KEYCODE_PAGE_UP to 0xff55,
        KeyEvent.KEYCODE_PAGE_DOWN to 0xff56,
        KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
        KeyEvent.KEYCODE_DPAD_UP to 0xff52,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
        KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
        KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
        KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3,
        KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
        KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        KeyEvent.KEYCODE_ALT_LEFT to 0xffe9,
        KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
        KeyEvent.KEYCODE_META_LEFT to 0xffeb,
        KeyEvent.KEYCODE_META_RIGHT to 0xffec,
        KeyEvent.KEYCODE_NUM_LOCK to 0xff7f,
        KeyEvent.KEYCODE_SCROLL_LOCK to 0xff14,
    ) + (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).associateWith { keyCode ->
        0xffbe + keyCode - KeyEvent.KEYCODE_F1
    }

    fun from(event: KeyEvent): Int? {
        keysyms[event.keyCode]?.let { return it }
        val unicode = event.unicodeChar
        if (unicode <= 0) return null
        return if (unicode <= 0xff) unicode else 0x01000000 or unicode
    }
}
