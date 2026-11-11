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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.repository.DashSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.slf4j.LoggerFactory
import javax.inject.Inject

data class DashSpendAccount(
    val provider: GiftCardProviderType,
    val isConnected: Boolean,
    val email: String?
)

data class DashSpendAccountsUIState(
    val accounts: List<DashSpendAccount> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * State for the gift card accounts sheet.
 *
 * The two services keep entirely separate credentials, so being signed in to one says nothing
 * about the other and both are reported independently here.
 */
@HiltViewModel
class DashSpendAccountsViewModel @Inject constructor(
    repositoryFactory: DashSpendRepositoryFactory
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(DashSpendAccountsViewModel::class.java)

        private val PROVIDERS = listOf(GiftCardProviderType.CTX, GiftCardProviderType.PiggyCards)
    }

    private val repositories: Map<GiftCardProviderType, DashSpendRepository> =
        PROVIDERS.associateWith { repositoryFactory.create(it) }

    private val _uiState = MutableStateFlow(DashSpendAccountsUIState())
    val uiState: StateFlow<DashSpendAccountsUIState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // PiggyCards renews an expired token as part of answering this, so it can reach the
            // network. Keep the whole pass off the main thread.
            val accounts = withContext(Dispatchers.IO) {
                PROVIDERS.map { provider ->
                    val repository = repositories.getValue(provider)
                    val connected = try {
                        repository.isUserSignedIn()
                    } catch (ex: Exception) {
                        // Treat an unanswerable check as disconnected: offering "Connect" to
                        // someone already signed in is recoverable, hiding it is not.
                        log.warn("could not check the ${provider.name} session: {}", ex.message)
                        false
                    }

                    DashSpendAccount(
                        provider = provider,
                        isConnected = connected,
                        email = if (connected) runCatching { repository.userEmail.first() }.getOrNull() else null
                    )
                }
            }

            _uiState.value = DashSpendAccountsUIState(accounts = accounts, isLoading = false)
        }
    }

    suspend fun disconnect(provider: GiftCardProviderType) {
        withContext(Dispatchers.IO) {
            repositories.getValue(provider).logout()
        }
        refresh()
    }
}
