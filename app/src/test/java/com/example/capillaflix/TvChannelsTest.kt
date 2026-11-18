package com.example.capillaflix

import org.junit.Assert.*
import org.junit.Test

class TvChannelsTest {
    @Test fun channelButtonsFollowTelevisionOrderInBothDirections() {
        assertEquals("mv", adjacentTelevision("ptv", 1)?.id)
        assertEquals("mv", adjacentTelevision("7tv", -1)?.id)
    }

    @Test fun channelButtonsWrapAtBothEnds() {
        assertEquals("ptv", adjacentTelevision("101", 1)?.id)
        assertEquals("101", adjacentTelevision("ptv", -1)?.id)
    }

    @Test fun radioAndClosedPlayerCannotSwitchTelevision() {
        assertNull(adjacentTelevision("onda", 1))
        assertNull(adjacentTelevision(null, -1))
    }
}
