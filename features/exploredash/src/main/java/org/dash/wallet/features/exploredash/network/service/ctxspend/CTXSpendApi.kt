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
package org.dash.wallet.features.exploredash.network.service.ctxspend

import com.google.gson.annotations.SerializedName
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GetMerchantResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.LoginRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.PurchaseGiftCardRequest
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.RefreshTokenResponse
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.VerifyEmailRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class Pagination(
    @SerializedName("page") val page: Int = 1,
    @SerializedName("pages") val pages: Int = 1,
    @SerializedName("perPage") val perPage: Int = 0,
    @SerializedName("total") val total: Int = 0
)

data class CTXMerchantInfo(
    @SerializedName("description") val description: String? = null,
    @SerializedName("instructions") val instructions: String? = null,
    @SerializedName("intro") val intro: String? = null,
    @SerializedName("terms") val terms: String? = null
)

data class MerchantResponse(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("type") val type: String? = null,
    @SerializedName("currency") val currency: String? = null,
    @SerializedName("denominations") val denominations: List<String>? = null,
    @SerializedName("denominationsType") val denominationsType: String? = null,
    @SerializedName("redeemType") val redeemType: String? = null,
    @SerializedName("savingsPercentage") val savingsPercentage: Int = 0,
    @SerializedName("cachedLocationCount") val cachedLocationCount: Int = 0,
    @SerializedName("cardImageUrl") val cardImageUrl: String? = null,
    @SerializedName("logoUrl") val logoUrl: String? = null,
    @SerializedName("mapPinUrl") val mapPinUrl: String? = null,
    @SerializedName("info") val info: CTXMerchantInfo? = null
)

data class MerchantsResponse(
    @SerializedName("pagination") val pagination: Pagination? = null,
    @SerializedName("result") val result: List<MerchantResponse>? = null
)

interface CTXSpendApi {
    @POST("login")
    suspend fun login(@Body loginRequest: LoginRequest): Response<Unit>

    @POST("verify-email")
    suspend fun verifyEmail(@Body verifyEmailRequest: VerifyEmailRequest): RefreshTokenResponse?

    @POST("gift-cards")
    suspend fun purchaseGiftCard(@Body purchaseGiftCardRequest: PurchaseGiftCardRequest): GiftCardResponse

    @GET("gift-cards")
    suspend fun getGiftCardByTxId(@Query("txid") txid: String): GiftCardResponse?

    @GET("gift-cards/{id}")
    suspend fun getGiftCardByOrderId(@Path("id") id: String): GiftCardResponse?

    @GET("merchants/{id}")
    suspend fun getMerchant(@Path("id") id: String): GetMerchantResponse?

    @GET("merchants")
    suspend fun getAllMerchants(
        @Query("perPage") perPage: Int = 20,
        @Query("page") page: Int = 1
    ): MerchantsResponse
}
