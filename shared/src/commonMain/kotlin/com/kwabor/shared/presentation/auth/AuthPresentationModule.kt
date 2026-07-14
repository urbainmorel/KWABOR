package com.kwabor.shared.presentation.auth

import org.koin.core.module.Module
import org.koin.dsl.module

internal val authPresentationModule: Module = module {
    factory { AuthPresenter(authRepository = get()) }
}
