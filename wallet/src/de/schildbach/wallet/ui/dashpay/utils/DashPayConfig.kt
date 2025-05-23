/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class DashPayConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
): BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf(
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = context.packageName + "_preferences",
            keysToMigrate = setOf(
                LAST_SEEN_NOTIFICATION_TIME.name
            )
        )
    )
) {
    companion object {
        const val DISABLE_NOTIFICATIONS: Long = -1

        const val PREFERENCES_NAME = "dashpay"
        val LAST_SEEN_NOTIFICATION_TIME = longPreferencesKey("last_seen_notification_time")
        val LAST_METADATA_PUSH = longPreferencesKey("last_metadata_push")
        val HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN = booleanPreferencesKey("has_dash_pay_info_screen_been_shown")
        val VOTING_INFO_SHOWN = booleanPreferencesKey("voting_info_shown")
        val MIX_DASH_SHOWN = booleanPreferencesKey("mix_dash_shown")
        val KEYS_DONT_ASK_AGAIN = booleanPreferencesKey("dont_ask_again_for_keys")
        val FIRST_TIME_VOTING = booleanPreferencesKey("first_time_voting")
        val CREDIT_INFO_SHOWN = booleanPreferencesKey("credit_info_shown")
        val TOPUP_COUNTER = intPreferencesKey("topup_counter")
        val USERNAME_VOTE_COUNTER = intPreferencesKey("username_vote_counter")
    }

    open suspend fun areNotificationsDisabled(): Boolean {
        return (get(LAST_SEEN_NOTIFICATION_TIME) ?: 0) == DISABLE_NOTIFICATIONS
    }

    open suspend fun disableNotifications() {
        set(LAST_SEEN_NOTIFICATION_TIME, DISABLE_NOTIFICATIONS)
    }


    suspend fun getTopupCounter(): Int {
        val counter = get(TOPUP_COUNTER) ?: 1
        set(TOPUP_COUNTER, counter + 1)
        return counter
    }
    
    suspend fun getUsernameVoteCounter(): Int {
        val counter = (get(USERNAME_VOTE_COUNTER) ?: 0) + 1
        set(USERNAME_VOTE_COUNTER, counter)
        return counter
    }
}
