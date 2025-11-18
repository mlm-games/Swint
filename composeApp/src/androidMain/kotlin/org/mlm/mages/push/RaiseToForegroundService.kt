package org.mlm.mages.push

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Minimal binder used by UnifiedPush distributors to temporarily raise app importance
 * while delivering a message, per spec.
 */
class RaiseToForegroundService : Service() {
    private val binder = Binder()
    override fun onBind(intent: Intent?): IBinder = binder
}