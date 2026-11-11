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

package org.dash.wallet.features.exploredash.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.MerchantCatalogEntry
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.dashspend.piggycards.model.Brand
import org.slf4j.LoggerFactory

/**
 * One gift card service's merchant catalogue.
 *
 * This build has no preloaded `explore.db`, so the merchant list is assembled from the gift card
 * services at runtime instead of being shipped as an encrypted database. Each service exposes a
 * different shape, so each gets an implementation here and the sync merges the results.
 */
interface MerchantCatalogSource {
    val provider: GiftCardProviderType

    /** Whether this source can be fetched right now, i.e. the user has a usable session. */
    suspend fun isAvailable(): Boolean

    /** Every merchant this service currently offers. Throws on network or protocol failure. */
    suspend fun fetch(): List<MerchantCatalogEntry>
}

/**
 * Narrows an arbitrary provider string to a denomination type the app understands.
 *
 * [DenominationType.fromString] throws on anything it does not recognise, and it is called from
 * UI code while rendering a merchant, so an unexpected value written here would surface as a
 * crash when the user taps the merchant rather than as a failed sync. Unknown values become
 * "fixed", which is the conservative choice: it shows explicit denominations rather than
 * inviting a free-form amount the service might reject.
 */
internal fun sanitizeDenominationsType(raw: String?): String {
    val value = raw?.trim()?.lowercase().orEmpty()
    return try {
        DenominationType.fromString(value).value
    } catch (_: IllegalArgumentException) {
        DenominationType.Fixed.value
    }
}

class CTXMerchantCatalogSource(
    private val repository: CTXSpendRepository
) : MerchantCatalogSource {
    companion object {
        private val log = LoggerFactory.getLogger(CTXMerchantCatalogSource::class.java)
    }

    override val provider = GiftCardProviderType.CTX

    override suspend fun isAvailable(): Boolean = repository.isUserSignedIn()

    override suspend fun fetch(): List<MerchantCatalogEntry> {
        val merchants = repository.getAllMerchants()
        log.info("CTX returned {} merchants", merchants.size)

        return merchants.mapNotNull { merchant ->
            val name = merchant.name?.trim()

            if (name.isNullOrEmpty()) {
                // Without a name there is nothing to show and nothing to merge on.
                log.info("skipping CTX merchant {} with no name", merchant.id)
                return@mapNotNull null
            }

            MerchantCatalogEntry(
                sourceId = merchant.id,
                name = name,
                active = merchant.enabled,
                savingsPercentage = merchant.savingsPercentage,
                denominationsType = sanitizeDenominationsType(merchant.denominationsType),
                redeemType = merchant.redeemType.orEmpty(),
                logoUrl = merchant.logoUrl ?: merchant.cardImageUrl
            )
        }
    }
}

class PiggyCardsMerchantCatalogSource(
    private val repository: PiggyCardsRepository
) : MerchantCatalogSource {
    companion object {
        private val log = LoggerFactory.getLogger(PiggyCardsMerchantCatalogSource::class.java)

        /**
         * How many brands to ask about at once.
         *
         * PiggyCards has no bulk endpoint for this: `brands/{country}` returns only ids and
         * names, so filling in each brand costs one more request. Running them all in parallel
         * would open a connection per brand against a service that is not ours to load-test,
         * and running them one at a time makes the sync take as long as the brand count. This
         * is the compromise.
         */
        private const val BRAND_FETCH_CONCURRENCY = 6
    }

    override val provider = GiftCardProviderType.PiggyCards

    override suspend fun isAvailable(): Boolean = repository.isUserSignedIn()

    override suspend fun fetch(): List<MerchantCatalogEntry> = coroutineScope {
        val brands = repository.getAllBrands()
        log.info("PiggyCards returned {} brands", brands.size)

        brands.chunked(BRAND_FETCH_CONCURRENCY).flatMap { chunk ->
            chunk.map { brand ->
                async { toEntry(brand) }
            }.awaitAll()
        }.filterNotNull()
    }

    private suspend fun toEntry(brand: Brand): MerchantCatalogEntry? {
        val name = brand.name.trim()

        if (name.isEmpty()) {
            log.info("skipping PiggyCards brand {} with no name", brand.id)
            return null
        }

        val giftCards = try {
            repository.getGiftCardsForBrand(brand.id)
        } catch (ex: Exception) {
            // One brand failing should not cost us the rest of the catalogue.
            log.warn("could not load PiggyCards gift cards for brand {}: {}", brand.id, ex.message)
            return null
        }

        // Only cards actually in stock can be bought, so a brand with none of them is listed
        // as inactive rather than dropped: it keeps its merchant row, and the row comes back
        // to life on the next sync without the id changing.
        val inStock = giftCards.filter { it.quantity > 0 }
        val best = inStock.maxByOrNull { it.discountPercentage }

        return MerchantCatalogEntry(
            sourceId = brand.id,
            name = name,
            active = inStock.isNotEmpty(),
            // PiggyCards quotes a percentage ("2.5" is 2.5%); the explore database stores
            // basis points, where 250 is the same discount.
            savingsPercentage = ((best?.discountPercentage ?: 0.0) * 100).toInt(),
            denominationsType = when {
                best == null -> DenominationType.Fixed.value
                best.isRange -> DenominationType.MinMax.value
                else -> DenominationType.Fixed.value
            },
            redeemType = "",
            logoUrl = best?.image ?: inStock.firstOrNull()?.image ?: giftCards.firstOrNull()?.image
        )
    }
}
