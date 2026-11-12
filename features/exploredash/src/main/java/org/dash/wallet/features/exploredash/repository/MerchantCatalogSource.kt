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
import org.dash.wallet.features.exploredash.network.service.ctxspend.MerchantResponse
import org.slf4j.LoggerFactory

/**
 * What one service had to say about its catalogue this run.
 *
 * The distinction that matters is whether the answer is *authoritative*: only an authoritative
 * answer lets the sync delete rows. Treating "I could not tell you" as "I offer nothing" is how
 * a user with a perfectly good account loses their merchant list to a flat tyre on the network.
 */
sealed interface CatalogAnswer {
    /**
     * The service listed its whole catalogue. Anything of its it does not list here is gone,
     * so the sync may remove those rows.
     */
    data class Complete(val entries: List<MerchantCatalogEntry>) : CatalogAnswer

    /**
     * The service answered, but not for everything it sells: some of the per-merchant requests
     * failed. The entries are still worth writing, but the gaps are not evidence of removal,
     * so the sync leaves this service's existing rows alone.
     */
    data class Partial(val entries: List<MerchantCatalogEntry>, val missing: Int) : CatalogAnswer
}

/**
 * One gift card service's merchant catalogue.
 *
 * This build has no preloaded `explore.db`, so the merchant list is assembled from the gift card
 * services at runtime instead of being shipped as an encrypted database. Each service exposes a
 * different shape, so each gets an implementation here and the sync merges the results.
 */
interface MerchantCatalogSource {
    val provider: GiftCardProviderType

    /**
     * Whether this source can be fetched right now, i.e. the user has a usable session.
     *
     * False does not mean the user signed out. The secure reads underneath this return null on
     * any failure (see [org.dash.wallet.common.data.BaseConfig]), so an expired token that
     * could not be renewed offline and a keystore the device can no longer decrypt both answer
     * false. Only [MerchantCatalogSync]'s record of a disconnect authorises removing rows.
     */
    suspend fun isAvailable(): Boolean

    /** Every merchant this service currently offers. Throws on network or protocol failure. */
    suspend fun fetch(): CatalogAnswer
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

    override suspend fun fetch(): CatalogAnswer {
        val merchants = repository.getAllMerchants()
        log.info("CTX returned {} merchants", merchants.size)

        // One request returns the whole catalogue, so either it threw or this is all of it.
        return CatalogAnswer.Complete(mapEntries(merchants))
    }

    private fun mapEntries(merchants: List<MerchantResponse>) =
        merchants.mapNotNull { merchant ->
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

        /**
         * What PiggyCards keeps of each sale, in basis points.
         *
         * The discount the API quotes is gross; the user receives it less this fee, which is
         * what [PiggyCardsRepository] subtracts everywhere it prices a card. The merchant list
         * has to quote the same net figure or it advertises a saving the purchase screen will
         * not honour.
         */
        private const val SERVICE_FEE_BASIS_POINTS = 150
    }

    override val provider = GiftCardProviderType.PiggyCards

    override suspend fun isAvailable(): Boolean = repository.isUserSignedIn()

    /**
     * Brands first, then one request per brand for the detail the list needs.
     *
     * A brand whose detail request fails is reported as [CatalogAnswer.Partial] rather than
     * quietly left out: an entry missing from the answer otherwise reads to the sync as a
     * merchant PiggyCards has stopped selling, and the row would be deleted over one timeout.
     */
    private class BrandFetch(val entry: MerchantCatalogEntry?, val failed: Boolean)

    override suspend fun fetch(): CatalogAnswer = coroutineScope {
        val brands = repository.getAllBrands()
        log.info("PiggyCards returned {} brands", brands.size)

        val results = brands.chunked(BRAND_FETCH_CONCURRENCY).flatMap { chunk ->
            chunk.map { brand ->
                async { toEntry(brand) }
            }.awaitAll()
        }

        val entries = results.mapNotNull { it.entry }
        val missing = results.count { it.failed }

        if (missing == 0) {
            CatalogAnswer.Complete(entries)
        } else {
            log.warn("{} of {} PiggyCards brands could not be loaded, keeping existing rows", missing, brands.size)
            CatalogAnswer.Partial(entries, missing)
        }
    }

    private suspend fun toEntry(brand: Brand): BrandFetch {
        val name = brand.name.trim()

        if (name.isEmpty()) {
            // Nothing to show and nothing to merge on. This is a complete answer about the
            // brand, not a gap in one, so it does not make the fetch partial.
            log.info("skipping PiggyCards brand {} with no name", brand.id)
            return BrandFetch(entry = null, failed = false)
        }

        val giftCards = try {
            repository.getGiftCardsForBrand(brand.id)
        } catch (ex: Exception) {
            // One brand failing should not cost us the rest of the catalogue, nor this brand's
            // own row: the fetch is reported partial so the sync deletes nothing.
            log.warn("could not load PiggyCards gift cards for brand {}: {}", brand.id, ex.message)
            return BrandFetch(entry = null, failed = true)
        }

        // Only cards actually in stock can be bought, so a brand with none of them is listed
        // as inactive rather than dropped: it keeps its merchant row, and the row comes back
        // to life on the next sync without the id changing.
        val inStock = giftCards.filter { it.quantity > 0 }
        val best = inStock.maxByOrNull { it.discountPercentage }

        val entry = MerchantCatalogEntry(
            sourceId = brand.id,
            name = name,
            active = inStock.isNotEmpty(),
            // PiggyCards quotes a percentage ("2.5" is 2.5%); the explore database stores
            // basis points, where 250 is the same discount. The service fee comes off here so
            // the list quotes what the purchase screen will actually give, and the floor keeps
            // a card discounted by less than the fee from advertising a negative saving.
            savingsPercentage = (((best?.discountPercentage ?: 0.0) * 100).toInt() - SERVICE_FEE_BASIS_POINTS)
                .coerceAtLeast(0),
            denominationsType = when {
                best == null -> DenominationType.Fixed.value
                best.isRange -> DenominationType.MinMax.value
                else -> DenominationType.Fixed.value
            },
            redeemType = "",
            logoUrl = best?.image ?: inStock.firstOrNull()?.image ?: giftCards.firstOrNull()?.image
        )

        return BrandFetch(entry, failed = false)
    }
}
