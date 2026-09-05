package com.newoether.agora.ramen

import android.content.Context
import com.newoether.agora.uma.UmaApplicationContext
import com.newoether.agora.util.Constants

/**
 * Process-wide state for the juece-ramen decision datasource (收集数据工作台):
 * the enable switch and the configured base URL, persisted in SharedPreferences and
 * mirrored into volatile fields so both the settings UI and RamenToolProvider read
 * the same values synchronously without touching disk on the hot path.
 */
object RamenDataSourceStore {
    private const val PREFS_FILE = "ramen_datasource"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"

    @Volatile private var loaded = false
    @Volatile private var cachedEnabled = false
    @Volatile private var cachedBaseUrl = Constants.RAMEN_DEFAULT_BASE_URL

    private val prefs by lazy {
        runCatching {
            UmaApplicationContext.require().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            prefs?.let { store ->
                cachedEnabled = store.getBoolean(KEY_ENABLED, false)
                cachedBaseUrl = store.getString(KEY_BASE_URL, null)
                    ?.takeIf { it.isNotBlank() && validateRamenBaseUrl(it) == null }
                    ?: Constants.RAMEN_DEFAULT_BASE_URL
            }
            loaded = true
        }
    }

    fun isEnabled(): Boolean {
        ensureLoaded()
        return cachedEnabled
    }

    fun baseUrl(): String {
        ensureLoaded()
        return cachedBaseUrl
    }

    fun setEnabled(value: Boolean) {
        cachedEnabled = value
        persist { it.putBoolean(KEY_ENABLED, value) }
    }

    fun setBaseUrl(value: String) {
        cachedBaseUrl = value.trim()
        persist { it.putString(KEY_BASE_URL, cachedBaseUrl) }
    }

    private inline fun persist(edit: (android.content.SharedPreferences.Editor) -> Unit) {
        runCatching {
            prefs?.let { store -> edit(store.edit()).apply() }
        }
    }
}
