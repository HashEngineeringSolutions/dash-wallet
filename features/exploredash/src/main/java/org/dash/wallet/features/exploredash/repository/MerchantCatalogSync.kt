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

import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.MerchantCatalogEntry
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.data.explore.model.MerchantType
import org.dash.wallet.features.exploredash.data.explore.model.PaymentMethod
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the merchant list from the gift card services the user is signed in to.
 *
 * Upstream ships a preloaded, encrypted `explore.db` containing merchants and ATMs. This build
 * has no access to it, so the merchant table is assembled here instead, from whichever of
 * [MerchantCatalogSource]s currently has a session. That makes the list a function of who is
 * logged in: signing out of a service removes its merchants on the next sync.
 *
 * A merchant sold by both services becomes one row with one `gift_card_providers` row per
 * service, which is what the schema and the merchant detail sheet's provider picker expect.
 */
class MerchantCatalogSync(
    private val merchantDao: MerchantDao,
    private val giftCardProviderDao: GiftCardProviderDao,
    private val sources: List<MerchantCatalogSource>,
    /**
     * Merchant ids this sync must not touch. The PiggyCards test merchants are written
     * separately and would otherwise look like stale rows from a service that is now signed
     * out, since they carry the same `source` as real PiggyCards merchants.
     */
    private val protectedMerchantIds: Set<String> = emptySet()
) {
    companion object {
        private val log = LoggerFactory.getLogger(MerchantCatalogSync::class.java)
    }

    data class Result(
        val merchantsWritten: Int,
        val providersWritten: Int,
        val merchantsRemoved: Int,
        val failedSources: List<String>
    )

    suspend fun sync(): Result {
        val entries = mutableMapOf<String, MutableList<Pair<MerchantCatalogSource, MerchantCatalogEntry>>>()
        val syncedProviders = mutableListOf<String>()
        val failedSources = mutableListOf<String>()

        for (source in sources) {
            val providerName = source.provider.name

            if (!source.isAvailable()) {
                // Not signed in. This is a normal state, not a failure: the service's merchants
                // are cleared below so the list never offers a purchase the user cannot make.
                log.info("{} has no session, its merchants will be removed", providerName)
                syncedProviders.add(providerName)
                continue
            }

            try {
                val fetched = source.fetch()
                syncedProviders.add(providerName)

                fetched.forEach { entry ->
                    val key = MerchantCatalogEntry.matchKey(entry.name)

                    if (key.isNotEmpty()) {
                        entries.getOrPut(key) { mutableListOf() }.add(source to entry)
                    }
                }

                log.info("{} contributed {} merchants", providerName, fetched.size)
            } catch (ex: Exception) {
                // Leave this service's existing rows alone rather than wiping a catalogue
                // because of one bad response. It is not added to syncedProviders, so the
                // delete pass below skips it.
                log.error("could not load the $providerName catalogue, keeping its existing rows", ex)
                failedSources.add(providerName)
            }
        }

        return write(entries, syncedProviders, failedSources)
    }

    private suspend fun write(
        entries: Map<String, List<Pair<MerchantCatalogSource, MerchantCatalogEntry>>>,
        syncedProviders: List<String>,
        failedSources: List<String>
    ): Result {
        // Everything these services had before this run. Anything still here afterwards that
        // the services no longer offer is stale and gets removed.
        val previouslySynced = giftCardProviderDao.getAllProviders()
            .filter { it.provider in syncedProviders }
            .map { it.merchantId }
            .toSet() - protectedMerchantIds

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val merchants = mutableListOf<Merchant>()
        val providerRows = mutableListOf<GiftCardProvider>()

        entries.forEach { (key, contributions) ->
            val merchantId = MerchantCatalogEntry.merchantIdFor(key)

            if (merchantId in protectedMerchantIds) {
                return@forEach
            }

            // The row the list renders is the best of what the services offer: the largest
            // discount, and the first logo available, since not every service supplies one.
            val best = contributions.maxByOrNull { it.second.savingsPercentage }!!.second
            val anyActive = contributions.any { it.second.active }

            merchants.add(
                Merchant(
                    merchantId = merchantId,
                    paymentMethod = PaymentMethod.GIFT_CARD,
                    redeemType = best.redeemType,
                    savingsPercentage = best.savingsPercentage,
                    denominationsType = best.denominationsType,
                    addDate = now,
                    updateDate = now
                ).apply {
                    name = best.name
                    active = anyActive
                    source = contributions.first().first.provider.name
                    sourceId = contributions.first().second.sourceId
                    logoLocation = contributions.firstNotNullOfOrNull { it.second.logoUrl }
                    website = contributions.firstNotNullOfOrNull { it.second.website }
                    // Neither service returns coordinates or a territory, so these merchants
                    // cannot be placed on the map or filtered by region. Marking them online
                    // keeps them out of the location-based queries, which would return them
                    // with no address and no way to navigate there.
                    type = MerchantType.ONLINE
                }
            )

            contributions.forEach { (source, entry) ->
                providerRows.add(
                    GiftCardProvider(
                        merchantId = merchantId,
                        provider = source.provider.name,
                        redeemType = entry.redeemType,
                        savingsPercentage = entry.savingsPercentage,
                        active = entry.active,
                        denominationsType = entry.denominationsType,
                        sourceId = entry.sourceId
                    )
                )
            }
        }

        val writtenIds = merchants.mapNotNull { it.merchantId }.toSet()

        // Drop each synced service's provider rows and rebuild them. Scoping the delete to the
        // service matters: a merchant sold by both keeps the other service's row even when
        // this one signed out or came back empty.
        syncedProviders.forEach { provider ->
            if (protectedMerchantIds.isEmpty()) {
                giftCardProviderDao.deleteByProvider(provider)
            } else {
                giftCardProviderDao.deleteByProviderExcept(provider, protectedMerchantIds.toList())
            }
        }

        // Replace rather than update: merchant.id is an autogenerate primary key, so saving a
        // row built here with id = 0 inserts a duplicate instead of overwriting.
        if (writtenIds.isNotEmpty()) {
            merchantDao.deleteByMerchantIds(writtenIds.toList())
        }

        merchantDao.save(merchants)
        giftCardProviderDao.insertAll(providerRows)

        // A merchant is only removed once nothing offers it any more. Checking after the
        // rewrite rather than before means a merchant sold by both services survives one of
        // them signing out, instead of disappearing along with its remaining purchase path.
        val stillOffered = giftCardProviderDao.getAllProviders().map { it.merchantId }.toSet()
        val staleIds = (previouslySynced - writtenIds - stillOffered).toList()

        if (staleIds.isNotEmpty()) {
            merchantDao.deleteByMerchantIds(staleIds)
            log.info("removed {} merchants no longer offered by any service", staleIds.size)
        }

        log.info(
            "merchant catalogue synced: {} merchants, {} provider rows, {} removed",
            merchants.size,
            providerRows.size,
            staleIds.size
        )

        return Result(
            merchantsWritten = merchants.size,
            providersWritten = providerRows.size,
            merchantsRemoved = staleIds.size,
            failedSources = failedSources
        )
    }
}
