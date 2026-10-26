package app.dumblaunch // Match your package name

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

class CustomViewGroup(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        // Intercept any touch in this area
        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Consume the touch event so it completely dies here
        return true
    }
}
