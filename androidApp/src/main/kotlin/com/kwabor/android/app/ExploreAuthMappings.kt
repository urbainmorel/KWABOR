package com.kwabor.android.app

import com.kwabor.android.presentation.auth.AuthProtectedAction
import com.kwabor.shared.presentation.explore.ExploreInteractionKind

internal fun ExploreInteractionKind.toProtectedAction(): AuthProtectedAction = when (this) {
    ExploreInteractionKind.Like -> AuthProtectedAction.Like
    ExploreInteractionKind.Favorite -> AuthProtectedAction.Favorite
}
