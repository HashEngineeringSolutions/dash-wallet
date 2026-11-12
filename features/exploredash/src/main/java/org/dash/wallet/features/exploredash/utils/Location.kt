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

package org.dash.wallet.features.exploredash.utils

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import androidx.annotation.RequiresPermission
import org.slf4j.LoggerFactory
import java.util.Locale

private val log = LoggerFactory.getLogger("Location")

/**
 * There is no fused location provider in this build, so this reads the most recent fix
 * the platform LocationManager has from any enabled provider.
 */
@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
suspend fun getCountryCodeFromLocation(context: Context): String {
    return try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ""
        val location = manager.getProviders(true)
            .asSequence()
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?: return ""
        val geocoder = Geocoder(context, Locale.getDefault())
        geocoder.getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.countryCode
            .orEmpty()
    } catch (e: Exception) {
        log.info("getCountryCodeFromLocation failed: ${e.message}")
        ""
    }
}
