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
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
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
    private val protectedMerchantIds: Set<String> = emptySet(),
    /**
     * Services the user has just disconnected.
     *
     * This is the only thing that authorises clearing a service's rows. A session check cannot
     * stand in for it: [MerchantCatalogSource.isAvailable] answers false both for "signed out"
     * and for "could not be determined", because the secure reads underneath it return null on
     * any failure — no network to renew an expired token, or a keystore the device can no
     * longer decrypt. Deleting on that answer loses a working user's whole merchant list to a
     * transient fault, and nothing short of signing out and back in rebuilds it.
     */
    private val disconnected: Set<GiftCardProviderType> = emptySet(),
    /**
     * Runs the write pass as one unit. Production passes the Room database's transaction, so a
     * kill in the middle cannot leave merchants whose provider rows have already been deleted:
     * those are invisible to the next run's stale sweep, which reads the merchants to keep from
     * the provider table, and would linger unpurchasable for good.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() }
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

        // Services whose answer this run was authoritative enough to delete rows on. A service
        // missing from this list keeps every row it already has.
        val authoritative = mutableListOf<String>()
        val failedSources = mutableListOf<String>()

        fun collect(source: MerchantCatalogSource, fetched: List<MerchantCatalogEntry>) {
            fetched.forEach { entry ->
                val key = MerchantCatalogEntry.matchKey(entry.name)

                if (key.isNotEmpty()) {
                    entries.getOrPut(key) { mutableListOf() }.add(source to entry)
                }
            }

            log.info("{} contributed {} merchants", source.provider.name, fetched.size)
        }

        for (source in sources) {
            val providerName = source.provider.name
            val userDisconnected = source.provider in disconnected

            if (!source.isAvailable()) {
                if (userDisconnected) {
                    // The user asked for this. Its merchants are cleared below so the list
                    // never offers a purchase they cannot make.
                    log.info("{} was disconnected, its merchants will be removed", providerName)
                    authoritative.add(providerName)
                } else {
                    // No usable session, but nobody said to forget this account. It may be an
                    // expired token that could not be renewed offline, or a secure read that
                    // failed. Either way the rows stay: a stale merchant is a far smaller harm
                    // than an empty list the user has no way to refill.
                    log.info("{} has no usable session, keeping its existing rows", providerName)
                }
                continue
            }

            try {
                when (val answer = source.fetch()) {
                    is CatalogAnswer.Complete -> {
                        authoritative.add(providerName)
                        collect(source, answer.entries)
                    }
                    is CatalogAnswer.Partial -> {
                        // Write what did arrive, but the gaps are not evidence that those
                        // merchants are gone, so this service authorises no deletions.
                        failedSources.add(providerName)
                        collect(source, answer.entries)
                        log.warn(
                            "the {} catalogue is incomplete ({} missing), keeping its existing rows",
                            providerName,
                            answer.missing
                        )
                    }
                }
            } catch (ex: Exception) {
                // Leave this service's existing rows alone rather than wiping a catalogue
                // because of one bad response. It is not authoritative, so the delete pass
                // below skips it.
                log.error("could not load the $providerName catalogue, keeping its existing rows", ex)
                failedSources.add(providerName)
            }
        }

        return write(entries, authoritative, failedSources)
    }

    private suspend fun write(
        entries: Map<String, List<Pair<MerchantCatalogSource, MerchantCatalogEntry>>>,
        authoritative: List<String>,
        failedSources: List<String>
    ): Result {
        val existing = giftCardProviderDao.getAllProviders()

        // Which merchants each service reported this run, so a service that answered for only
        // part of its catalogue can have exactly those rows replaced and no others.
        val reported = mutableMapOf<String, MutableSet<String>>()
        entries.forEach { (key, contributions) ->
            val merchantId = MerchantCatalogEntry.merchantIdFor(key)

            if (merchantId !in protectedMerchantIds) {
                contributions.forEach { (source, _) ->
                    reported.getOrPut(source.provider.name) { mutableSetOf() }.add(merchantId)
                }
            }
        }

        // Everything the authoritative services had before this run. Anything still here
        // afterwards that they no longer offer is stale and gets removed.
        val previouslySynced = existing
            .filter { it.provider in authoritative }
            .map { it.merchantId }
            .toSet() - protectedMerchantIds

        // Merchants kept alive by a row this write pass does not touch: a service that did not
        // answer authoritatively keeps every row it did not report. Such a merchant is still
        // purchasable through that service, so it must not be written inactive just because the
        // services that did answer have nothing active for it.
        val retainedActive = existing
            .filter { row ->
                row.provider !in authoritative &&
                    row.active &&
                    row.merchantId !in reported[row.provider].orEmpty()
            }
            .map { it.merchantId }
            .toSet()

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
            val anyActive = contributions.any { it.second.active } || merchantId in retainedActive

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

        var staleIds = emptyList<String>()

        // One unit of work. Halfway through, the merchant table and the provider table
        // disagree, and the stale sweep below reads the provider table to decide which
        // merchants to keep: a kill between the two would strand merchants that no later run
        // could find, let alone clean up.
        inTransaction {
            // Drop each authoritative service's provider rows and rebuild them. Scoping the
            // delete to the service matters: a merchant sold by both keeps the other service's
            // row even when this one was disconnected or came back empty.
            authoritative.forEach { provider ->
                if (protectedMerchantIds.isEmpty()) {
                    giftCardProviderDao.deleteByProvider(provider)
                } else {
                    giftCardProviderDao.deleteByProviderExcept(provider, protectedMerchantIds.toList())
                }
            }

            // A service that answered for only part of its catalogue still has to have the part
            // it did answer for replaced, or the insert below lands beside the old rows instead
            // of over them and the table grows a duplicate on every sync.
            reported.forEach { (provider, merchantIds) ->
                if (provider !in authoritative && merchantIds.isNotEmpty()) {
                    giftCardProviderDao.deleteByProviderAndMerchantIds(provider, merchantIds.toList())
                }
            }

            // Replace rather than update: merchant.id is an autogenerate primary key, so saving
            // a row built here with id = 0 inserts a duplicate instead of overwriting.
            if (writtenIds.isNotEmpty()) {
                merchantDao.deleteByMerchantIds(writtenIds.toList())
            }

            merchantDao.save(merchants)
            giftCardProviderDao.insertAll(providerRows)

            // A merchant is only removed once nothing offers it any more. Checking after the
            // rewrite rather than before means a merchant sold by both services survives one of
            // them signing out, instead of disappearing along with its remaining purchase path.
            val stillOffered = giftCardProviderDao.getAllProviders().map { it.merchantId }.toSet()
            staleIds = (previouslySynced - writtenIds - stillOffered).toList()

            if (staleIds.isNotEmpty()) {
                merchantDao.deleteByMerchantIds(staleIds)
                log.info("removed {} merchants no longer offered by any service", staleIds.size)
            }
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
