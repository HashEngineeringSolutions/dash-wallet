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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.features.exploredash.ExploreSyncWorker
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
    @ApplicationContext private val appContext: Context,
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

    /**
     * The refresh in flight, if any.
     *
     * Only one runs at a time. Checking a PiggyCards session renews an expired token, so an
     * overlapping check means two logins racing, and a login that lands after [disconnect] has
     * cleared the credentials writes its access token straight back over them: the account the
     * user just disconnected is signed in again, possibly after the sync that removed its
     * merchants has already run.
     */
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
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

    /**
     * Signs out of [provider] and rebuilds the merchant list without it.
     *
     * The rebuild belongs here rather than in a listener the caller installs: the sheet's
     * listeners are lost when the fragment is recreated, and a disconnect whose rebuild was
     * dropped leaves merchants in the list that can no longer be bought. This is also the only
     * place that tells the sync a removal was the user's doing — see
     * [ExploreSyncWorker.runForAccountChange].
     */
    suspend fun disconnect(provider: GiftCardProviderType) {
        // Before the logout, not after: a session check already in flight can be renewing the
        // token this is about to clear, and would write it back once it answered.
        refreshJob?.cancel()

        withContext(Dispatchers.IO) {
            repositories.getValue(provider).logout()
        }

        ExploreSyncWorker.runForAccountChange(appContext, disconnected = provider)
        refresh()
    }
}
