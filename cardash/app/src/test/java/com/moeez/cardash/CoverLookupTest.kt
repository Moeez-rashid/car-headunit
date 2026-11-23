package com.moeez.cardash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching rules, using songs actually seen on the head unit (2026-11-23). */
class CoverLookupTest {
    @Test fun exactMatchesAreAccepted() {
        assertTrue(CoverLookup.matches("you.", "pryme!", "you.", "pryme!"))
        assertTrue(CoverLookup.matches("SUN KISSES", "Citizen", "SUN KISSES", "CITIZEN"))
        assertTrue(CoverLookup.matches("what's luv?", "CLUB BOYBND", "what's luv?", "CLUB BOYBND"))
    }

    @Test fun wrongSongsAreRejected() {
        // Real first results from the search that are NOT the song playing.
        assertFalse(CoverLookup.matches("you.", "pryme!", "Show You Off", "Kizz Daniel & Pryme"))
        assertFalse(CoverLookup.matches("SUN KISSES", "Citizen", "Sun Kissed Skin", "The Upstanding Citizen"))
        assertFalse(CoverLookup.matches("SUN KISSES", "Citizen", "Apocalypse", "Cigarettes After Sex"))
        // Right title, unrelated artist (cover songs, same-named tracks).
        assertFalse(CoverLookup.matches("Hello", "Adele", "Hello", "Lionel Richie"))
    }

    @Test fun featuresVersionsAndSeveralArtists() {
        assertTrue(CoverLookup.matches("DOUBLE TAP", "ilyaugust, PROP", "Double Tap", "PROP & ilyaugust"))
        assertTrue(CoverLookup.matches("Whatever It Takes", "Imagine Dragons",
            "Whatever It Takes (Remastered)", "Imagine Dragons"))
        assertTrue(CoverLookup.matches("Peaches (feat. Daniel Caesar)", "Justin Bieber",
            "Peaches", "Justin Bieber, Daniel Caesar & GIVĒON"))
        assertEquals(listOf("ilyaugust", "PROP"), CoverLookup.artistNames("ilyaugust, PROP"))
        assertEquals(listOf("A", "B", "C"), CoverLookup.artistNames("A feat. B & C"))
    }

    @Test fun normalisation() {
        assertEquals("beyonce", CoverLookup.norm("Beyoncé"))
        assertEquals("whatsluv", CoverLookup.norm("what's luv?"))
        assertEquals("Song", CoverLookup.cleanTitle("Song - Live at Wembley"))
    }

    @Test fun emptyInfoNeverMatches() {
        assertFalse(CoverLookup.matches("", "Someone", "", "Someone"))
        assertFalse(CoverLookup.matches("Track", "", "Track", "Anyone"))
    }
}
