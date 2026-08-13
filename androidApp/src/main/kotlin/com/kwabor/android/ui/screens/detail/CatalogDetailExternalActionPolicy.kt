package com.kwabor.android.ui.screens.detail

import com.kwabor.android.detail.DetailExternalAction
import com.kwabor.android.detail.DetailExternalActionResult
import com.kwabor.android.detail.toIntentSpecOrNull
import com.kwabor.shared.presentation.detail.CatalogDetailContactUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailContentUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailDirectionsUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailTicketingUiModel
import com.kwabor.shared.presentation.detail.CatalogDetailUiModel

internal sealed interface CatalogDetailPrimaryExternalAction {
    data class Directions(val action: DetailExternalAction.Directions) : CatalogDetailPrimaryExternalAction

    data object Contact : CatalogDetailPrimaryExternalAction

    data class Ticket(
        val action: DetailExternalAction.Https,
        val enabled: Boolean,
    ) : CatalogDetailPrimaryExternalAction
}

internal data class CatalogDetailContactActions(
    val phone: DetailExternalAction.Phone?,
    val whatsapp: DetailExternalAction.WhatsApp?,
    val website: DetailExternalAction.Https?,
    val email: DetailExternalAction.Email?,
)

internal data class CatalogDetailExternalActionUiModel(
    val primary: CatalogDetailPrimaryExternalAction?,
    val secondaryDirections: DetailExternalAction.Directions?,
    val contact: CatalogDetailContactActions?,
    val menu: DetailExternalAction.Https?,
)

internal fun CatalogDetailUiModel.toExternalActionUiModel(): CatalogDetailExternalActionUiModel {
    if (isDemoContent) {
        return CatalogDetailExternalActionUiModel(
            primary = null,
            secondaryDirections = null,
            contact = null,
            menu = null,
        )
    }
    val directionsAction = directions?.toActionOrNull()
    val contactActions = content.establishmentContactOrNull(contact)
    return CatalogDetailExternalActionUiModel(
        primary = content.primaryExternalActionOrNull(directionsAction, contactActions),
        secondaryDirections = content.secondaryDirectionsOrNull(directionsAction),
        contact = contactActions,
        menu = (content as? CatalogDetailContentUiModel.Food)?.menuUrl.toHttpsActionOrNull(),
    )
}

private fun CatalogDetailContentUiModel.primaryExternalActionOrNull(
    directions: DetailExternalAction.Directions?,
    contact: CatalogDetailContactActions?,
): CatalogDetailPrimaryExternalAction? = when (this) {
    is CatalogDetailContentUiModel.Place -> directions?.let(CatalogDetailPrimaryExternalAction::Directions)
    is CatalogDetailContentUiModel.Lodging,
    is CatalogDetailContentUiModel.Food,
    is CatalogDetailContentUiModel.Nightlife,
    is CatalogDetailContentUiModel.Guide,
    -> contact?.let { CatalogDetailPrimaryExternalAction.Contact }
    is CatalogDetailContentUiModel.Event -> ticketActionOrNull()?.let { action ->
        CatalogDetailPrimaryExternalAction.Ticket(action = action, enabled = !isEnded)
    }
}

private fun CatalogDetailContentUiModel.secondaryDirectionsOrNull(
    directions: DetailExternalAction.Directions?,
): DetailExternalAction.Directions? = when (this) {
    is CatalogDetailContentUiModel.Place -> null
    is CatalogDetailContentUiModel.Lodging,
    is CatalogDetailContentUiModel.Food,
    is CatalogDetailContentUiModel.Nightlife,
    is CatalogDetailContentUiModel.Guide,
    is CatalogDetailContentUiModel.Event,
    -> directions
}

internal val DetailExternalActionResult.shouldShowGenericError: Boolean
    get() = when (this) {
        DetailExternalActionResult.Opened -> false
        DetailExternalActionResult.Rejected,
        DetailExternalActionResult.Unavailable,
        -> true
    }

private fun CatalogDetailContentUiModel.establishmentContactOrNull(
    contact: CatalogDetailContactUiModel?,
): CatalogDetailContactActions? = when (this) {
    is CatalogDetailContentUiModel.Place,
    is CatalogDetailContentUiModel.Event,
    -> null
    is CatalogDetailContentUiModel.Lodging,
    is CatalogDetailContentUiModel.Food,
    is CatalogDetailContentUiModel.Nightlife,
    is CatalogDetailContentUiModel.Guide,
    -> contact?.toActionsOrNull()
}

private fun CatalogDetailContactUiModel.toActionsOrNull(): CatalogDetailContactActions? {
    val actions = CatalogDetailContactActions(
        phone = phoneNumber?.let { number -> DetailExternalAction.Phone(number) }?.acceptedOrNull(),
        whatsapp = whatsappNumber?.let { number -> DetailExternalAction.WhatsApp(number) }?.acceptedOrNull(),
        website = websiteUrl.toHttpsActionOrNull(),
        email = emailAddress?.let { address -> DetailExternalAction.Email(address) }?.acceptedOrNull(),
    )
    return actions.takeIf { listOfNotNull(it.phone, it.whatsapp, it.website, it.email).isNotEmpty() }
}

private fun CatalogDetailDirectionsUiModel.toActionOrNull(): DetailExternalAction.Directions? =
    DetailExternalAction.Directions(
        latitude = latitude,
        longitude = longitude,
        label = label,
    ).acceptedOrNull()

private fun CatalogDetailContentUiModel.Event.ticketActionOrNull(): DetailExternalAction.Https? {
    val externalUrl = when (val value = ticketing) {
        is CatalogDetailTicketingUiModel.Free -> value.externalUrl
        is CatalogDetailTicketingUiModel.Paid -> value.externalUrl
    }
    return externalUrl.toHttpsActionOrNull()
}

private fun String?.toHttpsActionOrNull(): DetailExternalAction.Https? =
    this?.let { url -> DetailExternalAction.Https(url) }?.acceptedOrNull()

private fun <T : DetailExternalAction> T.acceptedOrNull(): T? = takeIf { action ->
    action.toIntentSpecOrNull() != null
}
