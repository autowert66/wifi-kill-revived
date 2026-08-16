package dev.a99.wifikill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OuiLookupTest {

    private val oui = mapOf(
        "001122" to "ExampleCo",        // MA-L (24-bit)
        "0011223" to "ExampleMedium",   // MA-M (28-bit)
        "001122334" to "ExampleSmall",  // MA-S (36-bit)
    )

    @Test
    fun matchPrefix_prefersLongestPrefix() {
        // Hits all three lengths; the MA-S (9 digit) entry must win.
        assertEquals("ExampleSmall", OuiLookup.matchPrefix(oui, "00:11:22:33:44:55"))
        // Hits MA-M + MA-L; the MA-M (7 digit) entry must win.
        assertEquals("ExampleMedium", OuiLookup.matchPrefix(oui, "00:11:22:3A:BB:CC"))
        // Hits only MA-L.
        assertEquals("ExampleCo", OuiLookup.matchPrefix(oui, "00:11:22:99:AA:BB"))
    }

    @Test
    fun matchPrefix_returnsNullForUnknownPrefix() {
        assertNull(OuiLookup.matchPrefix(oui, "FF:EE:DD:CC:BB:AA"))
    }

    @Test
    fun normalizeMac_acceptsCommonFormats() {
        assertEquals("001122334455", OuiLookup.normalizeMac("00:11:22:33:44:55"))
        assertEquals("001122334455", OuiLookup.normalizeMac("00-11-22-33-44-55"))
        assertEquals("001122334455", OuiLookup.normalizeMac("001122334455"))
        assertEquals("00AB1C2D3E4F", OuiLookup.normalizeMac("00:ab:1c:2d:3e:4f"))
    }

    @Test
    fun normalizeMac_rejectsMalformedInput() {
        assertNull(OuiLookup.normalizeMac("00:11:22:33:44"))
        assertNull(OuiLookup.normalizeMac(""))
        assertNull(OuiLookup.normalizeMac("00:11:22:33:44:55:66"))
    }
}
