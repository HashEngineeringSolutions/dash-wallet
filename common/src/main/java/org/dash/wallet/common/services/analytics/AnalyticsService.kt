/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.services.analytics

import android.os.Bundle
import android.util.Log
import org.dash.wallet.common.BuildConfig
import javax.inject.Inject

interface AnalyticsService {
    fun logEvent(event: String, params: Bundle)
    fun logError(error: Throwable, details: String? = null)
}

class Analytics {
    fun logEvent(event: String, params: Bundle) {

    }
    fun logError(throwable: Throwable) {

    }
}

class CrashReporter {
    fun setCrashlyticsCollectionEnabled(isDebug: Boolean) {

    }

    fun log(details: String) {

    }

    fun recordException(throwable: Throwable) {

    }
}

object Fireplace {
    val analytics = Analytics()
    val crashlytics = CrashReporter()
}

class FireplaceAnalyticsServiceImpl @Inject constructor() : AnalyticsService {
    private val fireplaceAnalytics = Fireplace.analytics
    private val crashlytics = Fireplace.crashlytics

    init {
        crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
    }

    override fun logEvent(event: String, params: Bundle) {
        if (BuildConfig.DEBUG) {
            Log.i("FIREPLACE", "Skip event logging in debug mode: $event")

            if (!params.isEmpty) {
                Log.i("FIREPLACE", "Parameters: ${params.keySet().joinToString("; ") { "${it}: ${params[it]}" } }")
            }

            return
        }

        try {
            fireplaceAnalytics.logEvent(event, params)
        } catch (ex: Exception) {
            logError(ex)
        }
    }

    override fun logError(error: Throwable, details: String?) {
        if (BuildConfig.DEBUG) {
            Log.i("FIREPLACE", "Skip error logging in debug mode: $error")
            details?.let { Log.i("FIREBASE", "Details: $details") }
            return
        }

        details?.let { crashlytics.log(details) }
        crashlytics.recordException(error)
    }

    companion object {
        private var analyticsService: FireplaceAnalyticsServiceImpl? = null

        @Deprecated("Inject AnalyticsService instead")
        fun getInstance(): FireplaceAnalyticsServiceImpl {
            if (analyticsService == null) {
                analyticsService = FireplaceAnalyticsServiceImpl()
            }

            return analyticsService!!
        }
    }
}