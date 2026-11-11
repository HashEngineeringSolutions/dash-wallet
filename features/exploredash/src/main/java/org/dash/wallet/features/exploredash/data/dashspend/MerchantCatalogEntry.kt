/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.features.exploredash.data.dashspend

import java.util.Locale
import java.util.UUID

/**
 * One merchant as a single gift card provider describes it.
 *
 * This is the provider-neutral shape that [org.dash.wallet.features.exploredash.repository
 * .MerchantCatalogSource] implementations produce. CTX and PiggyCards expose very different
 * payloads, so everything that differs between them is normalised here before the sync merges
 * entries and writes them to the explore database.
 */
data class MerchantCatalogEntry(
    /** The provider's own id for this merchant. Stored on the merchant row as `sourceId`. */
    val sourceId: String,
    val name: String,
    val active: Boolean,
    /** In basis points, matching `Merchant.savingsPercentage`: 100 = 1%. */
    val savingsPercentage: Int,
    val denominationsType: String,
    val redeemType: String,
    val logoUrl: String? = null,
    val website: String? = null
) {
    companion object {
        /**
         * Collapses a merchant name to a comparison key, so the same brand sold by both
         * providers merges into one row.
         *
         * This is deliberately crude: case and non-alphanumerics are dropped and nothing else.
         * It catches "Amazon" / "amazon " / "Amazon!" but not "Amazon" / "Amazon.com", which
         * stay separate and show up as two rows. Making this cleverer risks merging genuinely
         * different brands, which is the worse failure: a merged row offers a purchase path
         * that charges the wrong merchant.
         */
        fun matchKey(name: String): String =
            name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        /**
         * The app-owned merchant id for a given [matchKey].
         *
         * Neither provider's id can serve as the merchant id, because one merchant row may be
         * backed by both of them; the provider ids live on `gift_card_providers.sourceId`
         * instead. Deriving the id from the name keeps it stable across syncs, devices and
         * reinstalls, so gift cards already purchased still resolve to their merchant.
         */
        fun merchantIdFor(matchKey: String): String =
            UUID.nameUUIDFromBytes("dashspend:$matchKey".toByteArray()).toString()
    }
}
