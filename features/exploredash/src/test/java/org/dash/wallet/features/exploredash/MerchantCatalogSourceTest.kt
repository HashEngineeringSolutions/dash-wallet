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
import org.dash.wallet.features.exploredash.network.service.ctxspend.MerchantResponse
import org.dash.wallet.features.exploredash.repository.CTXMerchantCatalogSource
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.CatalogAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Covers which answers a source is willing to call authoritative, since only an authoritative
 * answer lets [org.dash.wallet.features.exploredash.repository.MerchantCatalogSync] delete rows.
 */
class MerchantCatalogSourceTest {
    private fun merchant(id: String, name: String?) = MerchantResponse(
        id = id,
        name = name,
        enabled = true,
        savingsPercentage = 100,
        denominationsType = "fixed",
        redeemType = "barcode"
    )

    @Test
    fun `a populated CTX catalogue is authoritative`() = runTest {
        val repository = mock<CTXSpendRepository> {
            onBlocking { getAllMerchants() } doReturn listOf(merchant("ctx-1", "Amazon"))
        }

        val answer = CTXMerchantCatalogSource(repository).fetch()

        assertTrue("a real catalogue should authorise cleanup", answer is CatalogAnswer.Complete)
        assertEquals(1, (answer as CatalogAnswer.Complete).entries.size)
    }

    @Test
    fun `an empty CTX response is not an authoritative empty catalogue`() = runTest {
        // getAllMerchants() skips a page whose `result` is null and defaults missing pagination
        // to one page, so a malformed 200 arrives here as an empty list. Calling that complete
        // would delete every CTX merchant.
        val repository = mock<CTXSpendRepository> {
            onBlocking { getAllMerchants() } doReturn emptyList()
        }

        val answer = CTXMerchantCatalogSource(repository).fetch()

        assertTrue("an empty answer must not authorise deletion", answer is CatalogAnswer.Partial)
    }

    @Test
    fun `a CTX catalogue of nothing but unusable rows is not authoritative`() = runTest {
        val repository = mock<CTXSpendRepository> {
            onBlocking { getAllMerchants() } doReturn listOf(merchant("ctx-1", null), merchant("ctx-2", " "))
        }

        val answer = CTXMerchantCatalogSource(repository).fetch()

        assertTrue("nameless rows leave nothing to merge on", answer is CatalogAnswer.Partial)
    }
}
