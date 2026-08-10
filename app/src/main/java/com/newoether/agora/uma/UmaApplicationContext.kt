package com.newoether.agora.uma

import android.content.Context

/** Process-lifetime application context for model-created Uma tool providers. */
object UmaApplicationContext {
    @Volatile private var value: Context? = null

    fun install(context: Context) {
        value = context.applicationContext
    }

    fun require(): Context = requireNotNull(value) { "Uma application context is not initialized" }
}
