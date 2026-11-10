package com.kavelipay.app.data

import com.kavelipay.app.ui.screens.formatarTelefone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaixinhaRegrasTest {

    @Test
    fun taxaAceitaVirgulaEPonto() {
        assertEquals("2.5", taxaParaApi("2,5"))
        assertEquals("2.5", taxaParaApi("2.5"))
        assertEquals("4.75", taxaParaApi(" 4,75 "))
    }

    @Test
    fun taxaRemoveZerosSobrando() {
        // O backend serializa taxa sem zeros: "3.00" e "3" são a mesma taxa.
        assertEquals("3", taxaParaApi("3.00"))
        assertEquals("3", taxaParaApi("3"))
    }

    @Test
    fun taxaRecusaInvalida() {
        assertNull(taxaParaApi(""))
        assertNull(taxaParaApi("0"))
        assertNull(taxaParaApi("-1"))
        assertNull(taxaParaApi("1000"))
        assertNull(taxaParaApi("abc"))
        assertNull(taxaParaApi("2,5,1"))
    }

    @Test
    fun telefoneFicaSoComDigitos() {
        assertEquals("11987654321", telefoneDigitos("(11) 98765-4321"))
        assertEquals("11987654321", telefoneDigitos("119876543219999"))
    }

    @Test
    fun telefoneExigeDdd() {
        assertTrue(telefoneValido("1132654321"))
        assertTrue(telefoneValido("11987654321"))
        assertFalse(telefoneValido("987654321"))
        assertFalse(telefoneValido(""))
    }

    @Test
    fun telefoneFormatado() {
        assertEquals("(11) 98765-4321", formatarTelefone("11987654321"))
        assertEquals("(11) 3265-4321", formatarTelefone("1132654321"))
        assertEquals("123", formatarTelefone("123"))
    }

    @Test
    fun mesLocalNoFormatoDaApi() {
        assertTrue(mesLocal().matches(Regex("\\d{4}-\\d{2}")))
    }
}
