package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test

class WinterDesignTest {
    @Test fun productionNeedsRemoteFlagAndNeverAcceptsPreviewFlag() {
        assertFalse(winterDesignAllowed(false,false))
        assertFalse(winterDesignAllowed(false,true))
        assertFalse(winterDesignAllowed(true,false))
        assertTrue(winterDesignAllowed(true,true))
        assertTrue(winterDesignAllowed(false,false,true))
        assertFalse(winterDesignAllowed(false,false,false))
    }
    @Test fun snowFieldRemainsBoundedAcrossFramesAndLongSessions() {
        assertEquals(32,winterParticles.size)
        winterParticles.forEach {p->
            assertTrue(p.x in 0f..1f)
            listOf(0f,.033f,100f,3600f,86400f).forEach{time->assertTrue(snowProgress(time,p) in 0f..<1f)}
            assertNotEquals(snowProgress(0f,p),snowProgress(1f,p))
        }
    }
    @Test fun iciclesAreStableBoundedAndDifferentAcrossRecords() {
        val patterns=(0..300).map {iciclesFor("record-$it")}
        assertTrue(patterns.distinct().size>290)
        assertEquals(setOf(2,3,4,5),patterns.map{it.size}.toSet())
        patterns.forEachIndexed {index,shards->
            assertEquals(shards,iciclesFor("record-$index"))
            shards.forEach {assertTrue(it.height in 8f..22f);assertTrue(it.width in 5f..9f)}
            shards.zipWithNext().forEach{(a,b)->assertTrue(a.left+a.width<b.left)}
            assertTrue(shards.last().left+shards.last().width<=80)
        }
    }
}
