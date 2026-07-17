package com.kwabor.shared.presentation.auth

import org.koin.core.module.Module
import org.koin.dsl.module

internal val authPresentationModule: Module = module {
    factory { AuthPresenter(authRepository = get()) }
    factory { RegistrationReducer() }
    factory {
        RegistrationPresenter(
            authRepository = get(),
            catalogRepository = get(),
            clockProvider = get(),
            reducer = get(),
        )
    }
}
