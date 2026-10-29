package com.takaotech.departureboard.live

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTextTest {

    @Test
    fun `maiuscole, accenti e spazi ai bordi non contano`() {
        assertTrue(SearchText(" Cantù ").matches("CANTU"))
        assertTrue(SearchText("cantu").matches("Cantù"))
        assertTrue(SearchText("forlì").isPrefixOf("FORLI"))
        assertFalse(SearchText("cantu").isPrefixOf("MARIANO COMENSE - CANTU"))
    }

    @Test
    fun `l'apostrofo tipografico vale come quello dritto`() {
        assertTrue(SearchText("sant’angelo").matches("SANT'ANGELO LODIGIANO"))
    }

    @Test
    fun `senza testo è vuota`() {
        assertTrue(SearchText("   ").isEmpty)
        assertFalse(SearchText("a").isEmpty)
    }
}
