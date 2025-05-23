/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.ui.coinjoin.CoinJoinActivity
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.more.AboutActivity
import de.schildbach.wallet.ui.more.SettingsViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.exchange_rates.ExchangeRatesDialog
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : LockScreenActivity() {
    private val log = LoggerFactory.getLogger(SettingsActivity::class.java)
    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()
    @Inject
    lateinit var systemActions: SystemActionsService
    @Inject
    lateinit var walletUIConfig: WalletUIConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appBar.toolbar.title = getString(R.string.settings_title)
        binding.appBar.toolbar.setNavigationOnClickListener { finish() }

        binding.about.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Settings.ABOUT)
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.localCurrency.setOnClickListener {
            lifecycleScope.launch {
                viewModel.logEvent(AnalyticsConstants.Settings.LOCAL_CURRENCY)
                val currentOption = walletUIConfig.getExchangeCurrencyCode()
                ExchangeRatesDialog(currentOption) { rate, _, dialog ->
                    setSelectedCurrency(rate.currencyCode)
                    dialog.dismiss()
                }.show(this@SettingsActivity)
            }
        }

        binding.rescanBlockchain.setOnClickListener { resetBlockchain() }
        binding.notifications.setOnClickListener { systemActions.openNotificationSettings() }
        binding.batteryOptimization.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    walletApplication,
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ) == PackageManager.PERMISSION_GRANTED &&
                !viewModel.isIgnoringBatteryOptimizations
            ) {
                AdaptiveDialog.create(
                    R.drawable.ic_bolt_border,
                    getString(R.string.battery_optimization_dialog_optimized_title),
                    getString(R.string.battery_optimization_dialog_message_optimized),
                    getString(R.string.permission_deny),
                    getString(R.string.permission_allow)
                ).show(this) { allow ->
                    if (allow == true) {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
            } else {
                AdaptiveDialog.create(
                    R.drawable.ic_transaction_received_border,
                    getString(R.string.battery_optimization_dialog_unrestricted_title),
                    getString(R.string.battery_optimization_dialog_message_not_optimized),
                    getString(R.string.close),
                    getString(R.string.battery_optimization_dialog_button_change)
                ).show(this) { allow ->
                    if (allow == true) {
                        // Show the list of non-optimized apps
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                        }
                    }
                }
            }
        }
        setBatteryOptimizationText()
        binding.coinjoin.setOnClickListener {
            lifecycleScope.launch {
                val shouldShowFirstTimeInfo = viewModel.shouldShowCoinJoinInfo()

                if (shouldShowFirstTimeInfo) {
                    viewModel.setCoinJoinInfoShown()
                }

                val intent = Intent(this@SettingsActivity, CoinJoinActivity::class.java)
                intent.putExtra(CoinJoinActivity.FIRST_TIME_EXTRA, shouldShowFirstTimeInfo)
                viewModel.logEvent(AnalyticsConstants.Settings.COINJOIN)
                startActivity(intent)
            }
        }

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { binding.localCurrencySymbol.text = it }
            .launchIn(lifecycleScope)

        viewModel.coinJoinMixingMode.observe(this) { mode ->
            if (mode == CoinJoinMode.NONE) {
                binding.coinjoinSubtitle.text = getText(R.string.turned_off)
                binding.coinjoinSubtitleIcon.isVisible = false
                binding.progressBar.isVisible = false
                binding.balance.isVisible = false
                binding.coinjoinProgress.isVisible = false
            } else {
                if (viewModel.coinJoinMixingStatus == MixingStatus.FINISHED) {
                    binding.coinjoinSubtitle.text = getString(R.string.coinjoin_progress_finished)
                    binding.coinjoinSubtitleIcon.isVisible = false
                    binding.progressBar.isVisible = false
                    binding.coinjoinProgress.isVisible = false
                } else {
                    @StringRes val statusId = when(viewModel.coinJoinMixingStatus) {
                        MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
                        MixingStatus.MIXING -> R.string.coinjoin_mixing
                        MixingStatus.PAUSED -> R.string.coinjoin_paused
                        MixingStatus.FINISHED -> R.string.coinjoin_progress_finished
                        else -> R.string.error
                    }

                    binding.coinjoinSubtitle.text = getString(statusId)
                    binding.coinjoinSubtitleIcon.isVisible = true
                    binding.progressBar.isVisible = viewModel.coinJoinMixingStatus == MixingStatus.MIXING
                    binding.coinjoinProgress.text = getString(R.string.percent, viewModel.mixingProgress.toInt())
                    binding.coinjoinProgress.isVisible = true
                    binding.balance.isVisible = true
                    binding.balance.text = getString(R.string.coinjoin_progress_balance, viewModel.mixedBalance, viewModel.walletBalance)
                }
            }
        }
    }

    private fun setBatteryOptimizationText() {
        binding.batterySettingsSubtitle.text = getString(
            if (viewModel.isIgnoringBatteryOptimizations) {
                R.string.battery_optimization_subtitle_unrestricted
            } else {
                R.string.battery_optimization_subtitle_optimized
            }
        )
    }

    override fun onResume() {
        super.onResume()
        setBatteryOptimizationText()
    }

    private fun resetBlockchain() {
        AdaptiveDialog.create(
            null,
            getString(R.string.preferences_initiate_reset_title),
            getString(R.string.preferences_initiate_reset_dialog_message),
            getString(R.string.button_cancel),
            getString(R.string.preferences_initiate_reset_dialog_positive)
        ).show(this) {
            if (it == true) {
                log.info("manually initiated blockchain reset")
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET)

                walletApplication.resetBlockchain()
                configuration.updateLastBlockchainResetTime()
                startActivity(MainActivity.createIntent(this@SettingsActivity))
            } else {
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS)
            }
        }
    }

    private fun setSelectedCurrency(code: String) {
        lifecycleScope.launch {
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, code)
            val balance = walletData.getWalletBalance()
            WalletBalanceWidgetProvider.updateWidgets(this@SettingsActivity, balance)
        }
    }
}
