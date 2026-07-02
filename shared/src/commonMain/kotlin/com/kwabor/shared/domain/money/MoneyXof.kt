package com.kwabor.shared.domain.money

import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult

class MoneyXof private constructor(val amount: Long) {
    companion object {
        fun fromAmount(amount: Long): DomainResult<MoneyXof> {
            if (amount < 0) {
                return DomainResult.Failure(DomainError.Validation("error.money.negative"))
            }

            return DomainResult.Success(MoneyXof(amount))
        }
    }

    override fun equals(other: Any?): Boolean = other is MoneyXof && amount == other.amount

    override fun hashCode(): Int = amount.hashCode()

    override fun toString(): String = "MoneyXof(amount=$amount)"
}
