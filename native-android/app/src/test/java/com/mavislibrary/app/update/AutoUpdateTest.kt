package com.mavislibrary.app.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoUpdateTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun notices() = UpdateNotices(PreferenceDataStoreFactory.create { tmp.newFile("prefs.preferences_pb").also { it.delete() } })

    @Test fun policyInstallsOnlyWhenSwitchedOnAndPermitted() {
        assertEquals(AutoUpdatePolicy.Action.INSTALL, AutoUpdatePolicy.decide(autoOn = true, canInstall = true, kidsMode = false))
        assertEquals(AutoUpdatePolicy.Action.NOTIFY, AutoUpdatePolicy.decide(autoOn = true, canInstall = false, kidsMode = false))
        assertEquals(AutoUpdatePolicy.Action.NOTIFY, AutoUpdatePolicy.decide(autoOn = false, canInstall = true, kidsMode = false))
        assertEquals(AutoUpdatePolicy.Action.NONE, AutoUpdatePolicy.decide(autoOn = true, canInstall = true, kidsMode = true))
    }

    @Test fun autoInstallDefaultsOffAndPersists() = runBlocking {
        val n = notices()
        assertFalse(n.autoInstall.first())
        n.setAutoInstall(true)
        assertTrue(n.autoInstall.first())
    }

    @Test fun updatedNoticeFiresOnceOnTheNewBuildOnly() = runBlocking {
        val n = notices()
        assertNull(n.consumeAutoInstalled(115))
        n.markAutoPending(116, "Auto updates")
        assertNull("old build still running: no 'updated' notice", n.consumeAutoInstalled(115))
        assertEquals("Auto updates", n.consumeAutoInstalled(116))
        assertNull("only once", n.consumeAutoInstalled(116))
    }
}
