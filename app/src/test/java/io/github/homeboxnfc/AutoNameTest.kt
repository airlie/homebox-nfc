package io.github.homeboxnfc

import io.github.homeboxnfc.homebox.TreeNode
import io.github.homeboxnfc.ui.AddViewModel
import io.github.homeboxnfc.ui.TreeIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNameTest {
    private val uid = "04:A2:3B:1C:55:80:00"

    @Test fun shortAndStable() {
        val name = AddViewModel.autoName(uid, null)
        assertTrue(name, Regex("Item [0-9a-f]{4}").matches(name))
        assertEquals(name, AddViewModel.autoName(uid, null))
        assertNotEquals(name, AddViewModel.autoName("04:A2:3B:1C:55:80:01", null))
    }

    @Test fun longerWhenTaken() {
        val taken = AddViewModel.autoName(uid, null)
        val index = TreeIndex(listOf(TreeNode("1", "Garage", "location", listOf(TreeNode("2", taken, "item", emptyList())))))
        val name = AddViewModel.autoName(uid, index)
        assertTrue(name, Regex("Item [0-9a-f]{6}").matches(name))
    }
}
