package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test

class TestIsolation036Test {
    @Test fun agentCannotUseRealOrUnmarkedMembership() {
        assertFalse(testMembershipAllowed(true,"test","real",false))
        assertFalse(testMembershipAllowed(true,"test","real",true))
        assertFalse(testMembershipAllowed(true,"test","test",false))
    }
    @Test fun isolatedCanonicalTestIdentityIsAllowed() {
        assertTrue(testMembershipAllowed(true,"test","test",true))
    }
    @Test fun ordinaryApkKeepsExistingAccountAccess() {
        assertTrue(testMembershipAllowed(false,"user","user",false))
    }
}
