package io.github.homeboxnfc

import io.github.homeboxnfc.ui.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun numbersInOrder() = assertEquals(
        listOf("Box 2", "box 3", "Box 10", "Drill", "drill bits", "Item 007a", "Item 7b"),
        listOf("Box 10", "Drill", "Item 7b", "box 3", "drill bits", "Box 2", "Item 007a").sortedWith(NaturalOrder),
    )
}
