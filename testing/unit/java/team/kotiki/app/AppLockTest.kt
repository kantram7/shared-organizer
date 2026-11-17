package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AppLockTest {
    @Test fun pinRequiresExactlySixAsciiDigits(){
        assertTrue(PinPolicy.valid("098765"))
        listOf("12345","1234567","12 456","１２３４５６","abcdef","").forEach{assertFalse(PinPolicy.valid(it))}
    }
    @Test fun derivationUsesSaltAndRejectsWrongPin(){
        val salt=ByteArray(32){it.toByte()}
        val chars="123456".toCharArray()
        val expected=PinPolicy.derive(chars,salt)
        assertTrue(chars.all{it=='\u0000'})
        assertTrue(MessageDigest.isEqual(expected,PinPolicy.derive("123456".toCharArray(),salt)))
        assertFalse(MessageDigest.isEqual(expected,PinPolicy.derive("654321".toCharArray(),salt)))
        assertFalse(MessageDigest.isEqual(expected,PinPolicy.derive("123456".toCharArray(),ByteArray(32){(it+1).toByte()})))
    }
    @Test fun retriesEscalateAndNeverOverflow(){
        assertEquals(0L,PinPolicy.delayMillis(4))
        assertEquals(30_000L,PinPolicy.delayMillis(5))
        assertEquals(60_000L,PinPolicy.delayMillis(6))
        assertEquals(900_000L,PinPolicy.delayMillis(Int.MAX_VALUE))
    }
    @Test fun wallClockChangesDoNotBypassRetryDelay(){
        assertEquals(20_000L,PinPolicy.remaining(5,100_000,50_000,Long.MAX_VALUE,60_000))
        assertEquals(20_000L,PinPolicy.remaining(5,100_000,50_000,0,60_000))
        assertEquals(0L,PinPolicy.remaining(5,100_000,50_000,0,80_000))
    }
    @Test fun resetElapsedClockCannotSkipDelay(){
        assertEquals(30_000L,PinPolicy.remaining(5,100_000,50_000,101_000,10))
    }
    @Test fun timeoutBoundaryLocksAndUnrelatedStartDoesNot(){
        assertFalse(PinPolicy.needsLock(0,-1,100))
        assertTrue(PinPolicy.needsLock(0,100,100))
        assertFalse(PinPolicy.needsLock(1,100,60_099))
        assertTrue(PinPolicy.needsLock(1,100,60_100))
        assertTrue(PinPolicy.needsLock(15,100,99))
    }
}
