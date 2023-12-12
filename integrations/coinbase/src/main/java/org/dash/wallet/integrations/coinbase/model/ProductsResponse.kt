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

package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProductsResponse(
    val products: List<Product>
): Parcelable

@Parcelize
data class Product(
    @SerializedName("product_id")
    val productId: String,
    val price: String,
    val status: String,
    @SerializedName("trading_disabled")
    val tradingDisabled: Boolean,
    @SerializedName("quote_currency_id")
    val quoteCurrencyId: String,
    @SerializedName("base_currency_id")
    val baseCurrencyId: String,
    val alias: String,
    @SerializedName("alias_to")
    val aliasTo: List<String>
): Parcelable
