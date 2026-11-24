/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.sync

import android.content.Context
import java.time.LocalDate

/** Minimal key/value storage so the budget logic can be unit-tested without Android. */
interface BudgetStore {
    fun getLong(key: String, default: Long): Long
    fun getString(key: String, default: String): String
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putLong(key: String, value: Long)
        fun putString(key: String, value: String)
    }
}

/**
 * Daily cap on how much data cloud backup may upload, with separate caps for Wi-Fi and mobile
 * data (0 = unlimited). Usage rolls over at local midnight. The cap is checked before each file
 * starts, so a file that is already running may push the day slightly past the limit; that keeps
 * a small cap (10 MB) from blocking a single bigger photo forever.
 */
class UploadDataBudget(
    private val store: BudgetStore,
    private val today: () -> String = { LocalDate.now().toString() }
) {

    @Synchronized
    fun limitBytes(metered: Boolean): Long =
        store.getLong(limitKey(metered), 0L).coerceAtLeast(0L) * BYTES_PER_MB

    @Synchronized
    fun limitMb(metered: Boolean): Long = store.getLong(limitKey(metered), 0L).coerceAtLeast(0L)

    @Synchronized
    fun setLimitMb(metered: Boolean, mb: Long) {
        store.edit { putLong(limitKey(metered), mb.coerceAtLeast(0L)) }
    }

    @Synchronized
    fun usedBytes(metered: Boolean): Long {
        rollOverIfNeeded()
        return store.getLong(usedKey(metered), 0L)
    }

    /** True when today's cap for this network type has been reached. */
    @Synchronized
    fun isExhausted(metered: Boolean): Boolean {
        val limit = limitBytes(metered)
        return limit > 0L && usedBytes(metered) >= limit
    }

    @Synchronized
    fun record(bytes: Long, metered: Boolean) {
        if (bytes <= 0L) return
        rollOverIfNeeded()
        val key = usedKey(metered)
        store.edit { putLong(key, store.getLong(key, 0L) + bytes) }
    }

    private fun rollOverIfNeeded() {
        val now = today()
        if (store.getString(KEY_DAY, "") == now) return
        store.edit {
            putString(KEY_DAY, now)
            putLong(usedKey(true), 0L)
            putLong(usedKey(false), 0L)
        }
    }

    private fun limitKey(metered: Boolean) = if (metered) KEY_LIMIT_CELLULAR else KEY_LIMIT_WIFI
    private fun usedKey(metered: Boolean) = if (metered) KEY_USED_CELLULAR else KEY_USED_WIFI

    companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        /** Quick choices in the UI; anything else is a custom value. 0 means unlimited. */
        val PRESETS_MB = listOf(0L, 10L, 20L, 50L, 100L)

        private const val KEY_DAY = "day"
        private const val KEY_LIMIT_CELLULAR = "limit_cellular_mb"
        private const val KEY_LIMIT_WIFI = "limit_wifi_mb"
        private const val KEY_USED_CELLULAR = "used_cellular_bytes"
        private const val KEY_USED_WIFI = "used_wifi_bytes"

        fun from(context: Context): UploadDataBudget {
            val prefs = context.applicationContext.getSharedPreferences("cloud_upload_budget", Context.MODE_PRIVATE)
            return UploadDataBudget(object : BudgetStore {
                override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
                override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
                override fun edit(block: BudgetStore.Editor.() -> Unit) {
                    val e = prefs.edit()
                    object : BudgetStore.Editor {
                        override fun putLong(key: String, value: Long) { e.putLong(key, value) }
                        override fun putString(key: String, value: String) { e.putString(key, value) }
                    }.block()
                    e.apply()
                }
            })
        }
    }
}
