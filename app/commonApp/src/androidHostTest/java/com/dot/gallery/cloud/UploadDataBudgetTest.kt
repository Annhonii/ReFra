package com.dot.gallery.cloud

import com.dot.gallery.cloud.sync.BudgetStore
import com.dot.gallery.cloud.sync.UploadDataBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemStore : BudgetStore {
    val m = HashMap<String, Any>()
    override fun getLong(key: String, default: Long) = (m[key] as? Long) ?: default
    override fun getString(key: String, default: String) = (m[key] as? String) ?: default
    override fun edit(block: BudgetStore.Editor.() -> Unit) {
        object : BudgetStore.Editor {
            override fun putLong(key: String, value: Long) { m[key] = value }
            override fun putString(key: String, value: String) { m[key] = value }
        }.block()
    }
}
class UploadDataBudgetTest {
    @Test fun unlimitedNeverExhausted() {
        val b = UploadDataBudget(MemStore()) { "d1" }
        b.record(10_000_000_000L, true)
        assertFalse(b.isExhausted(true))
    }
    @Test fun separateWifiAndCellularLimits() {
        val b = UploadDataBudget(MemStore()) { "d1" }
        b.setLimitMb(true, 10); b.setLimitMb(false, 500)
        b.record(11L * 1024 * 1024, true)
        assertTrue(b.isExhausted(true)); assertFalse(b.isExhausted(false))
        b.record(499L * 1024 * 1024, false); assertFalse(b.isExhausted(false))
        b.record(2L * 1024 * 1024, false); assertTrue(b.isExhausted(false))
    }
    @Test fun resetsNextDay() {
        var day = "d1"
        val b = UploadDataBudget(MemStore()) { day }
        b.setLimitMb(true, 10); b.record(20L * 1024 * 1024, true)
        assertTrue(b.isExhausted(true)); day = "d2"
        assertFalse(b.isExhausted(true)); assertEquals(0L, b.usedBytes(true))
    }
    @Test fun customValueKeptAndZeroMeansUnlimited() {
        val b = UploadDataBudget(MemStore()) { "d" }
        b.setLimitMb(false, 750); assertEquals(750L, b.limitMb(false))
        b.setLimitMb(false, -5); assertEquals(0L, b.limitMb(false))
    }
}
