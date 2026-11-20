package com.example.hamkit.data.location

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocationHelperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val helper = LocationHelper(context)

    @Test
    fun `failure texts are actionable`() {
        assertTrue(locationFailureText(LocationFailure.NO_PERMISSION).contains("权限"))
        assertTrue(locationFailureText(LocationFailure.LOCATION_OFF).contains("定位"))
        assertTrue(locationFailureText(LocationFailure.TIMEOUT).contains("超时"))
    }

    @Test
    fun `picks most accurate fresh cache`() {
        val now = System.currentTimeMillis()
        val coarse = Location("network").apply {
            accuracy = 60f
            time = now
        }
        val good = Location("gps").apply {
            accuracy = 25f
            time = now - 1_000
        }
        val expired = Location("gps").apply {
            accuracy = 10f
            time = now - 20 * 60 * 1000
        }
        val best = helper.pickBestCached(
            listOf(coarse, good, expired),
            now = now,
            maxAgeMs = 10 * 60 * 1000L,
            maxAccuracyM = 50f,
        )
        assertEquals(good, best)
    }

    @Test
    fun `rejects expired or inaccurate cache`() {
        val now = System.currentTimeMillis()
        val expired = Location("gps").apply {
            accuracy = 10f
            time = now - 20 * 60 * 1000
        }
        val bad = Location("gps").apply {
            accuracy = 80f
            time = now
        }
        assertNull(
            helper.pickBestCached(listOf(expired, bad), now, 10 * 60 * 1000L, 50f),
        )
    }
}
