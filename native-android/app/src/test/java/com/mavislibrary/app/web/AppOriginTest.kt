package com.mavislibrary.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOriginTest {
    @Test fun acceptsAppOrigin() = assertTrue(isAppOrigin(WEB_ORIGIN))

    @Test fun acceptsAppPathsAndHashes() {
        assertTrue(isAppOrigin("$WEB_ORIGIN/index.html#/shelf"))
        assertTrue(isAppOrigin("$WEB_ORIGIN#/shelf"))
        assertTrue(isAppOrigin("$WEB_ORIGIN:443/#/shelf"))
    }

    @Test fun rejectsNull() = assertFalse(isAppOrigin(null))

    @Test fun rejectsOtherSite() = assertFalse(isAppOrigin("https://example.org/"))

    @Test fun rejectsLookalikeHostsAndUserInfo() {
        assertFalse(isAppOrigin("$WEB_ORIGIN.evil.com/"))
        assertFalse(isAppOrigin("$WEB_ORIGIN@evil.com/"))
        assertFalse(isAppOrigin("$WEB_ORIGIN\\@evil.com/"))
    }

    @Test fun rejectsWrongSchemePortAndMalformedUrls() {
        assertFalse(isAppOrigin("http://localhost/"))
        assertFalse(isAppOrigin("$WEB_ORIGIN:444/"))
        assertFalse(isAppOrigin("$WEB_ORIGIN:invalid/"))
        assertFalse(isAppOrigin(""))
    }
}
