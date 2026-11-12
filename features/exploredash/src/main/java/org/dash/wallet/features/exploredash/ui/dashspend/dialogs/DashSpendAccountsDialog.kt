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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.StyleRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.databinding.DialogDashSpendAccountsBinding
import org.dash.wallet.features.exploredash.databinding.ItemDashSpendAccountBinding

/**
 * Lets the user connect or disconnect each gift card service.
 *
 * The services hold separate credentials, so being signed in to one does not help with the
 * other, and until this existed the only way to reach a service's login was to tap a merchant
 * that service already sold. That made the second account unreachable: its merchants only
 * appear once it is connected.
 */
@AndroidEntryPoint
class DashSpendAccountsDialog : OffsetDialogFragment(R.layout.dialog_dash_spend_accounts) {
    companion object {
        /**
         * Result key for "the user asked to connect [RESULT_PROVIDER]".
         *
         * A fragment result rather than a listener field: the login flow lives in another
         * fragment, so this sheet can be recreated while it is open, and a listener set when
         * the sheet was first shown does not survive that. The caller registers for this in
         * its own onViewCreated, where re-creation puts it back.
         */
        const val REQUEST_CONNECT = "dash_spend_accounts_connect"
        const val RESULT_PROVIDER = "provider"

        fun newInstance() = DashSpendAccountsDialog()
    }

    @StyleRes
    override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogDashSpendAccountsBinding::bind)
    private val viewModel by viewModels<DashSpendAccountsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.accountsProgress.isVisible = state.isLoading
                    renderAccounts(state.accounts)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The login flow runs in a separate fragment, so this sheet can come back to a
        // different answer than the one it rendered.
        viewModel.refresh()
    }

    private fun renderAccounts(accounts: List<DashSpendAccount>) {
        val container = binding.accountsContainer
        container.removeAllViews()

        accounts.forEach { account ->
            val row = ItemDashSpendAccountBinding.inflate(LayoutInflater.from(requireContext()), container, false)
            row.accountLogo.setImageResource(account.provider.logo)
            row.accountName.text = account.provider.name
            row.accountStatus.text = when {
                !account.isConnected -> getString(R.string.dash_spend_account_not_connected)
                account.email.isNullOrEmpty() -> getString(R.string.dash_spend_account_connected_no_email)
                else -> getString(R.string.dash_spend_account_connected, account.email)
            }
            row.accountAction.text = getString(
                if (account.isConnected) {
                    R.string.dash_spend_account_disconnect
                } else {
                    R.string.dash_spend_account_connect
                }
            )
            row.accountAction.setOnClickListener {
                if (account.isConnected) {
                    confirmDisconnect(account.provider)
                } else {
                    // Close first: the login flow replaces this screen, and leaving the sheet
                    // behind it would put it back on top when the user returns.
                    dismiss()
                    setFragmentResult(REQUEST_CONNECT, bundleOf(RESULT_PROVIDER to account.provider))
                }
            }

            container.addView(row.root)
        }
    }

    private fun confirmDisconnect(provider: GiftCardProviderType) {
        AdaptiveDialog.create(
            null,
            getString(R.string.dash_spend_disconnect_title, provider.name),
            getString(R.string.dash_spend_disconnect_message),
            getString(R.string.button_cancel),
            getString(R.string.dash_spend_account_disconnect)
        ).show(requireActivity()) { confirmed ->
            if (confirmed == true) {
                // The rebuild of the merchant list is the view model's job, not a listener's:
                // see DashSpendAccountsViewModel.disconnect.
                lifecycleScope.launch { viewModel.disconnect(provider) }
            }
        }
    }
}
