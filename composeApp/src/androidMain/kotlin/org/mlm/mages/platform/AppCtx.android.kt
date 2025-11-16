package org.mlm.mages.platform

import android.content.Context

object AppCtx {
    @Volatile private var app: Context? = null
    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }
    fun get(): Context? = app
}