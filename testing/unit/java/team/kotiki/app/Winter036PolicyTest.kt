package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test

class Winter036PolicyTest {
    @Test fun pausedAppSnowDoesNotDisableAuthSnow(){
        assertFalse(winterSnowVisible(true,false,false))
        assertTrue(winterSnowVisible(true,false,true))
        assertTrue(winterSnowVisible(true,true,false))
        assertFalse(winterSnowVisible(false,true,true))
    }
    @Test fun agentCohortCanNeverEnableWinterInPhoneBuild(){
        assertFalse(remoteWinterAllowed(false,true,false))
        assertTrue(remoteWinterAllowed(false,true,true))
        assertTrue(remoteWinterAllowed(true,false,false))
        assertFalse(remoteWinterAllowed(false,false,true))
    }
}
