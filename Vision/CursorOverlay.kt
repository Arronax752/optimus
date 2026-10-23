package com.example.optimus.vision
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
class CursorOverlay(context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val cursor = View(context)
    private val params = WindowManager.LayoutParams(
        40,
        40,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )
    fun setRed() {
        (cursor.background as GradientDrawable).setColor(Color.RED)
    }
    fun setBlue() {
        (cursor.background as GradientDrawable).setColor(Color.CYAN)
    }
    fun setGreen(){
        (cursor.background as GradientDrawable).setColor(Color.GREEN)
    }
    init {
        params.gravity = Gravity.TOP or Gravity.START
        val shape = GradientDrawable()
        shape.shape = GradientDrawable.OVAL
        shape.setColor(Color.CYAN)
        cursor.background = shape
        wm.addView(cursor, params)
    }
    fun move(x: Float, y: Float) {
        params.x = x.toInt()
        params.y = y.toInt()
        wm.updateViewLayout(cursor, params)
    }
    fun remove() {
        wm.removeView(cursor)
    }
}
