package com.kwabor.shared.presentation.guide

import com.kwabor.shared.domain.guide.GuideDiscoveryRepository
import org.koin.core.module.Module
import org.koin.dsl.module

internal val guideDiscoveryPresentationModule: Module = module {
    factory { GuideDiscoveryPresenter(repository = get<GuideDiscoveryRepository>()) }
}
