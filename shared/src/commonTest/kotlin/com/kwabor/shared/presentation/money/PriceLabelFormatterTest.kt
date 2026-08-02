package com.kwabor.shared.presentation.money

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.money.MoneyXof
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceLabelFormatterTest {
    @Test
    fun compactXof_formatsCanonicalBoundaries() {
        assertEquals(FREE_LABEL, PriceLabelFormatter.compactXof(price = null, freeLabel = FREE_LABEL))
        assertEquals(FREE_LABEL, PriceLabelFormatter.compactXof(money(0), FREE_LABEL))
        assertEquals("9 999 FCFA", PriceLabelFormatter.compactXof(money(9_999), FREE_LABEL))
        assertEquals("25 k FCFA", PriceLabelFormatter.compactXof(money(25_000), FREE_LABEL))
        assertEquals("1 M FCFA", PriceLabelFormatter.compactXof(money(1_000_000), FREE_LABEL))
        assertEquals("1,0 M FCFA", PriceLabelFormatter.compactXof(money(1_000_001), FREE_LABEL))
        assertEquals("1,2 M FCFA", PriceLabelFormatter.compactXof(money(1_250_000), FREE_LABEL))
        assertEquals("1,4 M FCFA", PriceLabelFormatter.compactXof(money(1_350_000), FREE_LABEL))
        assertEquals("2,0 M FCFA", PriceLabelFormatter.compactXof(money(1_950_000), FREE_LABEL))
    }

    private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("Invalid test money")
    }
}

private const val FREE_LABEL = "Gratuit"
