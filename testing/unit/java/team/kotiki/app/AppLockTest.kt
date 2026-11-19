package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AppLockTest {
    @Test fun pinAllowsFourToTwelveAsciiDigitsAndKeepsExistingSixDigitPins(){
        listOf("0987","12345","098765").forEach{assertTrue(PinPolicy.valid(it))}
        listOf("123","1234567","123456789012","1234567890123","12 456","１２３４５６","abcdef","").forEach{assertFalse(PinPolicy.valid(it))}
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
    private class SessionState(var unlocked:Boolean=false)
    @Test fun sessionSurvivesBackgroundAndActivityRecreation(){
        val registry=ProcessSession<SessionState>{it.unlocked=false}
        val state=registry.get("user-one"){SessionState()};state.unlocked=true
        registry.retainFor("user-one")
        assertSame(state,registry.get("user-one"){SessionState()})
        assertTrue(state.unlocked)
    }
    @Test fun explicitCloseAndAccountSwitchRequireNewVerification(){
        val registry=ProcessSession<SessionState>{it.unlocked=false}
        val first=registry.get("one"){SessionState(true)}
        assertFalse(registry.get("two"){SessionState()}.unlocked)
        assertFalse(first.unlocked)
        registry.get("two"){SessionState()}.unlocked=true
        registry.clear()
        assertFalse(registry.get("two"){SessionState()}.unlocked)
    }
    @Test fun aNewProcessCannotRestoreAnUnlockedSession(){
        val old=ProcessSession<SessionState>{it.unlocked=false}
        old.get("one"){SessionState()}.unlocked=true
        val new=ProcessSession<SessionState>{it.unlocked=false}
        assertFalse(new.get("one"){SessionState()}.unlocked)
    }
}
