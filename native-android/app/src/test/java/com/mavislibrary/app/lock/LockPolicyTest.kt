package com.mavislibrary.app.lock

import org.junit.Assert.*
import org.junit.Test

class LockPolicyTest {
    private var time = 0L
    private val policy = LockPolicy({ time })

    @Test fun coldStartRequiresAuthenticationWhenEnabled() {
        policy.setEnabled(true)
        assertEquals(LockPolicy.State.LOCKED, policy.state)
        assertFalse(policy.canShowAdult(false))
        policy.authenticated()
        assertTrue(policy.canShowAdult(false))
    }

    @Test fun briefBackgroundStaysUnlocked() {
        policy.setEnabled(true)
        policy.authenticated()
        policy.background()
        time = 60_000
        policy.foreground()
        assertEquals(LockPolicy.State.UNLOCKED, policy.state)
    }

    @Test fun longBackgroundRequiresAuthentication() {
        policy.setEnabled(true)
        policy.authenticated()
        policy.background()
        time = 60_001
        policy.foreground()
        assertEquals(LockPolicy.State.NEEDS_AUTH, policy.state)
    }

    @Test fun disabledNeverLocks() {
        policy.setEnabled(true)
        policy.setEnabled(false)
        policy.background()
        time = 999_999
        policy.foreground()
        assertEquals(LockPolicy.State.UNLOCKED, policy.state)
    }

    @Test fun kidsModeNeverBlockedEvenWhenAdultIsLocked() {
        policy.setEnabled(true)
        assertTrue(policy.canShowAdult(true))
        assertFalse(policy.canShowAdult(false))
    }

    @Test fun cancelledPromptDoesNotUnlock() {
        policy.setEnabled(true)
        policy.background()
        time = 61_000
        policy.foreground()
        assertEquals(LockPolicy.State.NEEDS_AUTH, policy.state)
    }

    @Test fun ownerCodeNeverPushedBeforeUnlockOrAfterBackgroundTimeout() {
        assertFalse(ownerCodeAllowed(null, policy.state, false, false))
        policy.setEnabled(true)
        assertFalse(ownerCodeAllowed(true, policy.state, true, false))
        policy.authenticated()
        assertTrue(ownerCodeAllowed(true, policy.state, true, false))
        policy.background()
        time = 60_001
        policy.foreground()
        assertFalse(ownerCodeAllowed(true, policy.state, true, false))
        assertFalse(ownerCodeAllowed(true, policy.state, true, true))
        policy.authenticated()
        assertFalse(ownerCodeAllowed(true, policy.state, true, true))
    }

    @Test fun secureFlagOnlyWhenEnabled() {
        assertFalse(secureWindowRequired(null))
        assertFalse(secureWindowRequired(false))
        assertTrue(secureWindowRequired(true))
    }

    @Test fun unavailableAuthenticationCannotEnableLock() {
        assertFalse(lockEnrollmentAvailable(androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertFalse(lockEnrollmentAvailable(androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertTrue(lockEnrollmentAvailable(androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test fun clockResetRequiresAuthentication() {
        policy.setEnabled(true)
        policy.authenticated()
        time = 1000
        policy.background()
        time = 0
        policy.foreground()
        assertEquals(LockPolicy.State.NEEDS_AUTH, policy.state)
    }
}
