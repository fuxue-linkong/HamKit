package com.example.hamkit.data.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hamkit.data.privacy.PrivacyConsentStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OnboardingStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        PrivacyConsentStore.clear(context)
    }

    @Test
    fun `initially not done`() {
        assertFalse(OnboardingStore.isDone(context))
    }

    @Test
    fun `mark done`() {
        OnboardingStore.markDone(context)
        assertTrue(OnboardingStore.isDone(context))
    }

    @Test
    fun `needs agreement update after old consent`() {
        PrivacyConsentStore.accept(context, location = true, aprsPublic = false)
        assertTrue(OnboardingStore.needsAgreementUpdate(context))
    }
}
