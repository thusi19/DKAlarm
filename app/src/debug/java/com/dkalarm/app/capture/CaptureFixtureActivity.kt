package com.dkalarm.app.capture

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView

/** Debug-only test display containing a known screenshot, never a live account. */
class CaptureFixtureActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setImageBitmap(BitmapFactory.decodeFile(java.io.File(cacheDir,"capture-fixture.png").absolutePath))
            contentDescription="DK_CAPTURE_FIXTURE"
        })
    }
}
