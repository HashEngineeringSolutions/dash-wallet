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

package org.dash.wallet.features.exploredash.services

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Looper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.data.explore.model.GeoBounds
import org.dash.wallet.features.exploredash.di.FusedLocationProviderClient
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.*

data class UserLocation(var latitude: Double, var longitude: Double, var accuracy: Double)

interface UserLocationStateInt {
    fun observeUpdates(): Flow<UserLocation>
    fun getCurrentLocationAddress(lat: Double, lng: Double): Address?
    fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double
    fun distanceBetween(location1: UserLocation, location2: UserLocation): Double
    fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double
    fun getRadiusBounds(centerLat: Double, centerLng: Double, radius: Double): GeoBounds
}

class UserLocationState
@Inject
constructor(private val context: Context, private val client: FusedLocationProviderClient) : UserLocationStateInt {
    companion object {
        private const val UPDATE_INTERVAL_SECS = 10L
        private const val FASTEST_UPDATE_INTERVAL_SECS = 2L
        private const val EARTH_RADIUS = 6371009 // in meters
        private val log = LoggerFactory.getLogger(UserLocationState::class.java)
    }

    @SuppressLint("MissingPermission")
    override fun observeUpdates(): Flow<UserLocation> = callbackFlow {

    }

    override fun getCurrentLocationAddress(lat: Double, lng: Double): Address? {
        return try {
            val geocoder = Geocoder(context, GenericUtils.getDeviceLocale())
            val addresses = geocoder.getFromLocation(lat, lng, 1)

            if (!addresses.isNullOrEmpty()) {
                val locality = addresses[0].locality
                val cityName = if (locality.isNullOrEmpty()) addresses[0].adminArea else locality
                Address(addresses[0].countryName, cityName)
            } else {
                null
            }
        } catch (e: Exception) {
            log.info("GeocoderException ${e.message}")
            null
        }
    }

    override fun distanceBetweenCenters(bounds1: GeoBounds, bounds2: GeoBounds): Double {
        return distanceBetween(bounds1.centerLat, bounds1.centerLng, bounds2.centerLat, bounds2.centerLng)
    }

    override fun distanceBetween(location1: UserLocation, location2: UserLocation): Double {
        return distanceBetween(location1.latitude, location1.longitude, location2.latitude, location2.longitude)
    }

    override fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val sindLat = sin(dLat / 2)
        val sindLng = sin(dLng / 2)

        val a = sindLat.pow(2.0) + (sindLng.pow(2.0) * cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)))

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS * c // output distance, in METERS
    }

    override fun getRadiusBounds(centerLat: Double, centerLng: Double, radius: Double): GeoBounds {
        return GeoBounds(0.0,0.0,0.0,0.0,0.0,0.0,0.0f)
    }
}
