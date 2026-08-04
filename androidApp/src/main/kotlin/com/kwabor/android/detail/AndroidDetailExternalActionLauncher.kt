package com.kwabor.android.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun interface DetailExternalIntentDispatcher {
    fun dispatch(spec: DetailExternalIntentSpec): Boolean
}

internal class AndroidDetailExternalActionLauncher private constructor(
    private val dispatcher: DetailExternalIntentDispatcher,
) : DetailExternalActionLauncher {
    constructor(context: Context) : this(
        dispatcher = DetailExternalIntentDispatcher { spec ->
            try {
                val intent = Intent(spec.action.toAndroidAction(), Uri.parse(spec.uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )

    internal constructor(dispatcher: (DetailExternalIntentSpec) -> Boolean) : this(
        dispatcher = DetailExternalIntentDispatcher(dispatcher),
    )

    override fun launch(action: DetailExternalAction): DetailExternalActionResult {
        val spec = action.toIntentSpecOrNull() ?: return DetailExternalActionResult.Rejected
        return if (dispatcher.dispatch(spec)) {
            DetailExternalActionResult.Opened
        } else {
            DetailExternalActionResult.Unavailable
        }
    }
}

private fun DetailExternalIntentAction.toAndroidAction(): String = when (this) {
    DetailExternalIntentAction.Dial -> Intent.ACTION_DIAL
    DetailExternalIntentAction.View -> Intent.ACTION_VIEW
}
