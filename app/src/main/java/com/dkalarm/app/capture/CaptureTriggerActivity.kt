package com.dkalarm.app.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** A direct notification Activity closes the notification shade before the delayed shot. */
class CaptureTriggerActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        startService(Intent(this,ScreenCaptureService::class.java).setAction(ScreenCaptureService.TAKE))
        finish()
    }
}
