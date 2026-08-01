package com.poskds.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingCountParserTest {
    @Test
    fun `explicit cooking count is parsed from text or content description`() {
        assertEquals(3, CookingCountParser.explicitCount("조리중 3", null))
        assertEquals(12, CookingCountParser.explicitCount(null, "조리중\n12"))
    }

    @Test
    fun `bare cooking label is ambiguous and does not become zero`() {
        assertEquals(null, CookingCountParser.explicitCount("조리중", null))
        assertEquals(null, CookingCountParser.explicitCount("", "완료 5"))
    }

    @Test
    fun `only explicit empty-state message means zero`() {
        assertTrue(CookingCountParser.isExplicitEmptyState("조리할 주문이 없습니다", null))
        assertFalse(CookingCountParser.isExplicitEmptyState("조리중", null))
        assertFalse(CookingCountParser.isExplicitEmptyState("완료 5", null))
    }
}
