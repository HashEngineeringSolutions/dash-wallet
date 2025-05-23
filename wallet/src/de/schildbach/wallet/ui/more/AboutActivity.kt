/*
 * Copyright 2019 Dash Core Group.
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

package de.schildbach.wallet.ui.more

import android.annotation.SuppressLint
import android.content.*
import android.content.Intent.ACTION_VIEW
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.VersionMessage
import org.bitcoinj.params.MainNetParams
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.features.exploredash.ExploreSyncWorker
import org.slf4j.LoggerFactory
import kotlin.math.pow
import kotlin.math.sqrt


@AndroidEntryPoint
class AboutActivity : LockScreenActivity(), SensorEventListener {
    // variables for shake detection
    private val SHAKE_THRESHOLD = 1.50f // m/S**2

    private val MIN_TIME_BETWEEN_SHAKES_MILLISECS = 1000
    private var mLastShakeTime: Long = 0
    private lateinit var mSensorMgr: SensorManager

    companion object {
        private val log = LoggerFactory.getLogger(AboutActivity::class.java)
    }

    private val viewModel by viewModels<AboutViewModel>()
    private lateinit var binding: ActivityAboutBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        binding.appBar.setNavigationOnClickListener { finish() }

        val appName = getString(R.string.app_name_short)
        binding.title.text = "${getString(R.string.about_title)} $appName"
        binding.appVersionName.text = getString(R.string.about_version_name, BuildConfig.VERSION_NAME)
        binding.buildNumber.text = getString(R.string.about_build_number, BuildConfig.VERSION_CODE % 100)
        binding.libraryVersionName.text = getString(
            R.string.about_credits_bitcoinj_title,
            VersionMessage.BITCOINJ_VERSION
        )
        binding.platformVersionName.text = getString(
            R.string.about_credits_platform_title,
            BuildConfig.DPP_VERSION
        )
        binding.githubLink.setOnClickListener {
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(binding.githubLink.text.toString())
            startActivity(i)
        }
        binding.reviewAndRate.setOnClickListener { viewModel.reviewApp() }
        binding.contactSupport.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Settings.ABOUT_SUPPORT)
            handleReportIssue()
        }

        setContentView(binding.root)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    private fun handleReportIssue() {
        if (!isFinishing) {
            ContactSupportDialogFragment.newInstance(
                getString(R.string.report_issue_dialog_title_issue),
                getString(R.string.report_issue_dialog_message_issue),
            ).show(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val curTime = System.currentTimeMillis()
            if (curTime - mLastShakeTime > MIN_TIME_BETWEEN_SHAKES_MILLISECS) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val acceleration = sqrt(x.toDouble().pow(2.0) +
                        y.toDouble().pow(2.0) +
                        z.toDouble().pow(2.0)) - SensorManager.GRAVITY_EARTH

                if (acceleration > SHAKE_THRESHOLD) {
                    mLastShakeTime = curTime
                    log.info("Shake detected: developer mode changing to ${!configuration.developerMode}")
                    configuration.developerMode = if (!configuration.developerMode) {
                        Toast.makeText(this, R.string.about_developer_mode, LENGTH_LONG).show()
                        true
                    } else {
                        Toast.makeText(this, R.string.about_developer_mode_disabled, LENGTH_LONG).show()
                        false
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Ignore
    }
}
