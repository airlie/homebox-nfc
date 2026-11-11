package io.github.homeboxnfc

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenNameTest {
    private val skip = listOf("box", "bin", "tote", "crate", "bag")
    private fun short(name: String, stop: Boolean = true, max: Int = 20) = SpokenName.shorten(name, stop, skip, max)

    @Test fun skipsPrefixAndStopsAtSymbol() = assertEquals("12", short("Box 12 - Christmas lights"))
    @Test fun prefixWithColon() = assertEquals("Christmas lights", short("Box: Christmas lights"))
    @Test fun keepsLoneFillerWord() = assertEquals("Box", short("Box"))
    @Test fun skipIsCaseInsensitive() = assertEquals("Garage tools", short("TOTE Garage tools"))
    @Test fun apostropheInsideWordKept() = assertEquals("Dad's tools", short("Dad's tools (old)"))
    @Test fun cutsAtWordBoundary() = assertEquals("Christmas lights and", short("Christmas lights and ornaments for tree"))
    @Test fun longFirstWordReadWhole() = assertEquals("Supercalifragilisticexpialidocious", short("Supercalifragilisticexpialidocious thing"))
    @Test fun noLimit() = assertEquals("Christmas lights and ornaments for tree", short("Christmas lights and ornaments for tree", max = 0))
    @Test fun symbolsKeptWhenOff() = assertEquals("12 - Xmas", short("Box 12 - Xmas", stop = false))
    @Test fun leadingSymbolOnly() = assertEquals("Hammer", short("#Hammer"))
    @Test fun allSymbolsFallsBack() = assertEquals("---", short("---"))
}
