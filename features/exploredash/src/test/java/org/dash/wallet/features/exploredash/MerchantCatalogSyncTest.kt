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

package org.dash.wallet.features.exploredash

import kotlinx.coroutines.test.runTest
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderDao
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.MerchantCatalogEntry
import org.dash.wallet.features.exploredash.data.explore.MerchantDao
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CatalogAnswer
import org.dash.wallet.features.exploredash.repository.MerchantCatalogSource
import org.dash.wallet.features.exploredash.repository.MerchantCatalogSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/**
 * Covers the merge rules in [MerchantCatalogSync]: one merchant row per brand regardless of how
 * many services sell it, and removal only once no service offers it any more.
 */
class MerchantCatalogSyncTest {
    /** In-memory stand-in for the two tables the sync writes. */
    private class FakeStore {
        val merchants = mutableMapOf<String, Merchant>()
        val providers = mutableListOf<GiftCardProvider>()

        fun removeProviders(predicate: (GiftCardProvider) -> Boolean): Int {
            val before = providers.size
            providers.removeAll(predicate)
            return before - providers.size
        }
    }

    private fun merchantDao(store: FakeStore): MerchantDao = mock {
        onBlocking { save(any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as List<Merchant>).forEach { store.merchants[it.merchantId!!] = it }
        }
        onBlocking { deleteByMerchantIds(any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val ids = invocation.arguments[0] as List<String>
            val before = store.merchants.size
            ids.forEach { store.merchants.remove(it) }
            before - store.merchants.size
        }
    }

    private fun providerDao(store: FakeStore): GiftCardProviderDao = mock {
        onBlocking { getAllProviders() } doAnswer { store.providers.toList() }
        onBlocking { insert(any()) } doAnswer { invocation ->
            store.providers.add(invocation.arguments[0] as GiftCardProvider)
            Unit
        }
        onBlocking { insertAll(any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            store.providers.addAll(invocation.arguments[0] as List<GiftCardProvider>)
            Unit
        }
        onBlocking { deleteByProvider(any()) } doAnswer { invocation ->
            val provider = invocation.arguments[0] as String
            store.removeProviders { it.provider == provider }
        }
        onBlocking { deleteByProviderAndMerchantIds(any(), any()) } doAnswer { invocation ->
            val provider = invocation.arguments[0] as String
            @Suppress("UNCHECKED_CAST")
            val ids = invocation.arguments[1] as List<String>
            store.removeProviders { it.provider == provider && it.merchantId in ids }
        }
        onBlocking { deleteByProviderExcept(any(), any()) } doAnswer { invocation ->
            val provider = invocation.arguments[0] as String
            @Suppress("UNCHECKED_CAST")
            val keep = invocation.arguments[1] as List<String>
            store.removeProviders { it.provider == provider && it.merchantId !in keep }
        }
        onBlocking { deleteByMerchantIds(any()) } doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val ids = invocation.arguments[0] as List<String>
            store.removeProviders { it.merchantId in ids }
        }
    }

    private fun source(
        provider: GiftCardProviderType,
        available: Boolean = true,
        entries: List<MerchantCatalogEntry> = emptyList(),
        failure: Exception? = null,
        /** Mirrors a service that answered for some of its brands but not all of them. */
        missing: Int = 0
    ) = object : MerchantCatalogSource {
        override val provider = provider
        override suspend fun isAvailable() = available
        override suspend fun fetch(): CatalogAnswer = when {
            failure != null -> throw failure
            missing > 0 -> CatalogAnswer.Partial(entries, missing)
            else -> CatalogAnswer.Complete(entries)
        }
    }

    private fun entry(
        name: String,
        sourceId: String = "1",
        savingsPercentage: Int = 100,
        active: Boolean = true,
        logoUrl: String? = null
    ) = MerchantCatalogEntry(
        sourceId = sourceId,
        name = name,
        active = active,
        savingsPercentage = savingsPercentage,
        denominationsType = "fixed",
        redeemType = "",
        logoUrl = logoUrl
    )

    @Test
    fun `brand sold by both services becomes one merchant with two providers`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1", 100))),
                source(GiftCardProviderType.PiggyCards, entries = listOf(entry("amazon ", "77", 250)))
            )
        ).sync()

        assertEquals("the brand should collapse into one row", 1, store.merchants.size)
        assertEquals(2, store.providers.size)

        val merchant = store.merchants.values.single()
        assertEquals("the better of the two discounts should be shown", 250, merchant.savingsPercentage)
        assertEquals(
            "each service keeps its own id",
            setOf("ctx-1", "77"),
            store.providers.map { it.sourceId }.toSet()
        )
        assertEquals(
            setOf(GiftCardProviderType.CTX.name, GiftCardProviderType.PiggyCards.name),
            store.providers.map { it.provider }.toSet()
        )
    }

    @Test
    fun `merchant id is stable across syncs so purchases keep resolving`() = runTest {
        val store = FakeStore()
        val sources = listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))))

        MerchantCatalogSync(merchantDao(store), providerDao(store), sources).sync()
        val firstId = store.merchants.keys.single()

        MerchantCatalogSync(merchantDao(store), providerDao(store), sources).sync()

        assertEquals("a second sync must not duplicate the merchant", 1, store.merchants.size)
        assertEquals("a second sync must not renumber the merchant", firstId, store.merchants.keys.single())
        assertEquals("a second sync must not duplicate provider rows", 1, store.providers.size)
    }

    @Test
    fun `signing out of one service keeps a merchant the other still sells`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))),
                source(GiftCardProviderType.PiggyCards, entries = listOf(entry("Amazon", "77")))
            )
        ).sync()

        // CTX is disconnected; PiggyCards still has a session and still sells the brand.
        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(GiftCardProviderType.CTX, available = false),
                source(GiftCardProviderType.PiggyCards, entries = listOf(entry("Amazon", "77")))
            ),
            disconnected = setOf(GiftCardProviderType.CTX)
        ).sync()

        assertEquals("the merchant should survive, it is still purchasable", 1, store.merchants.size)
        assertEquals(1, store.providers.size)
        assertEquals(GiftCardProviderType.PiggyCards.name, store.providers.single().provider)
    }

    @Test
    fun `signing out of the only service removes its merchants`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))))
        ).sync()
        assertEquals(1, store.merchants.size)

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, available = false)),
            disconnected = setOf(GiftCardProviderType.CTX)
        ).sync()

        assertTrue("nothing can be bought, so nothing should be listed", store.merchants.isEmpty())
        assertTrue(store.providers.isEmpty())
    }

    @Test
    fun `a failing service keeps its existing rows instead of wiping them`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))))
        ).sync()

        val result = MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, failure = RuntimeException("502 Bad Gateway")))
        ).sync()

        assertEquals(listOf(GiftCardProviderType.CTX.name), result.failedSources)
        assertEquals("a bad response must not empty the merchant list", 1, store.merchants.size)
        assertEquals(1, store.providers.size)
    }

    @Test
    fun `protected merchants are left alone`() = runTest {
        val store = FakeStore()
        val testMerchantId = "2e393eee-4508-47fe-954d-66209333fc96"
        store.merchants[testMerchantId] = Merchant(merchantId = testMerchantId).apply { name = "Test" }
        store.providers.add(
            GiftCardProvider(
                merchantId = testMerchantId,
                provider = GiftCardProviderType.PiggyCards.name,
                redeemType = "",
                savingsPercentage = 0,
                active = true,
                denominationsType = "fixed",
                sourceId = "177"
            )
        )

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.PiggyCards, available = false)),
            protectedMerchantIds = setOf(testMerchantId),
            disconnected = setOf(GiftCardProviderType.PiggyCards)
        ).sync()

        assertTrue("the test merchant must survive a PiggyCards sign-out", store.merchants.containsKey(testMerchantId))
        assertEquals(1, store.providers.size)
    }

    @Test
    fun `a service that cannot be checked keeps its merchants`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))))
        ).sync()

        // No session, and the user disconnected nothing: an expired token that could not be
        // renewed offline, or a secure read that failed, both land here.
        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, available = false))
        ).sync()

        assertEquals("an unanswerable session check must not empty the list", 1, store.merchants.size)
        assertEquals(1, store.providers.size)
    }

    @Test
    fun `an incomplete catalogue does not remove the brands missing from it`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(
                    GiftCardProviderType.PiggyCards,
                    entries = listOf(entry("Amazon", "77"), entry("Target", "78"))
                )
            )
        ).sync()
        assertEquals(2, store.merchants.size)

        // Target's detail request failed this time, so it is absent from the answer. That is a
        // gap in the answer, not evidence PiggyCards stopped selling it.
        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(
                    GiftCardProviderType.PiggyCards,
                    entries = listOf(entry("Amazon", "77")),
                    missing = 1
                )
            )
        ).sync()

        assertEquals("a brand missing from an incomplete answer must survive", 2, store.merchants.size)
        assertEquals(2, store.providers.size)
    }

    @Test
    fun `a merchant kept alive by a retained provider stays active`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1"))),
                source(GiftCardProviderType.PiggyCards, entries = listOf(entry("Amazon", "77")))
            )
        ).sync()

        // CTX has the brand but cannot sell it; PiggyCards fails, so its active row is kept.
        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(
                source(
                    GiftCardProviderType.CTX,
                    entries = listOf(entry("Amazon", "ctx-1", active = false))
                ),
                source(GiftCardProviderType.PiggyCards, failure = RuntimeException("502 Bad Gateway"))
            )
        ).sync()

        assertEquals(
            "PiggyCards can still sell it, so the merchant must not be written inactive",
            true,
            store.merchants.values.single().active
        )
    }

    @Test
    fun `the write pass runs inside one transaction`() = runTest {
        val store = FakeStore()
        var wrapped = false

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", "ctx-1")))),
            inTransaction = { block ->
                wrapped = true
                block()
            }
        ).sync()

        assertTrue("the rewrite must be atomic, or a kill mid-way strands merchants", wrapped)
        assertEquals(1, store.merchants.size)
    }

    @Test
    fun `inactive entries are listed but marked inactive`() = runTest {
        val store = FakeStore()

        MerchantCatalogSync(
            merchantDao(store),
            providerDao(store),
            listOf(source(GiftCardProviderType.CTX, entries = listOf(entry("Amazon", active = false))))
        ).sync()

        assertEquals(false, store.merchants.values.single().active)
    }
}
