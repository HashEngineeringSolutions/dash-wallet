package org.dash.wallet.features.exploredash.data.dashspend

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GiftCardProviderDao {
    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId")
    suspend fun getProvidersByMerchantId(merchantId: String): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE provider = :provider")
    suspend fun getProvidersByProviderName(provider: String): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers")
    suspend fun getAllProviders(): List<GiftCardProvider>

    @Query("SELECT * FROM gift_card_providers WHERE merchantId = :merchantId AND provider = :provider")
    suspend fun getProviderByMerchantId(merchantId: String, provider: String): GiftCardProvider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: GiftCardProvider)

    /**
     * Insert a whole catalogue in one transaction. Inserting row by row costs a transaction
     * and an fsync each, which measured ~0.2s per row on an emulator: 45 seconds to write the
     * 223 rows a two-service sync produces.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<GiftCardProvider>)

    @Query("DELETE FROM gift_card_providers WHERE merchantId IN (:merchantIds)")
    suspend fun deleteByMerchantIds(merchantIds: List<String>): Int

    @Query("DELETE FROM gift_card_providers WHERE provider = :provider")
    suspend fun deleteByProvider(provider: String): Int

    @Query("DELETE FROM gift_card_providers WHERE provider = :provider AND merchantId NOT IN (:keepMerchantIds)")
    suspend fun deleteByProviderExcept(provider: String, keepMerchantIds: List<String>): Int
}
