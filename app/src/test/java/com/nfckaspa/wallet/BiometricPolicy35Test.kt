package com.nfckaspa.wallet

import android.security.keystore.KeyProperties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BiometricPolicy35Test {
    @Test fun android35UsesZeroDurationAndStrongBiometricsOnly() {
        val spec=Crypto.keySpec()
        assertTrue(spec.isUserAuthenticationRequired);assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertEquals(0,spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG,spec.userAuthenticationType)
    }
}
