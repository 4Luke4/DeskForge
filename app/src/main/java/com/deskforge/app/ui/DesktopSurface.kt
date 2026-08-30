package com.deskforge.app.ui

import android.content.Context
import android.graphics.Color
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView

/**
 * Android input boundary for the native desktop surface. Input is consumed only when the view has
 * focus; JNI forwarding will attach here when the X11 renderer process is packaged.
 */
class DesktopSurface(context: Context) : SurfaceView(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(18, 22, 27))
        contentDescription = context.getString(com.deskforge.app.R.string.desktop_preview)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isPointer = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        return if (isPointer) true else super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        requestFocus()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = true

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = true
}
