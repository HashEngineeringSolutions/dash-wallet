/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.features.exploredash

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.FireplaceNetworkException
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.repository.CTXMerchantCatalogSource
import org.dash.wallet.features.exploredash.repository.CTXSpendRepository
import org.dash.wallet.features.exploredash.repository.DashSpendRepositoryFactory
import org.dash.wallet.features.exploredash.repository.ExploreDataSyncStatus
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import org.dash.wallet.features.exploredash.repository.MerchantCatalogSync
import org.dash.wallet.features.exploredash.repository.PiggyCardsMerchantCatalogSource
import org.dash.wallet.features.exploredash.repository.PiggyCardsRepository
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.features.exploredash.utils.PiggyCardsConstants.SUPPORT_PIGGY_CARDS_TEST_MERCHANT
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants
import org.dash.wallet.features.exploredash.utils.PiggyCardsTestMerchants.PIGGY_CARDS_TEST_FIXED_MERCHANT_ID
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

@HiltWorker
class ExploreSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val analytics: AnalyticsService,
    private val exploreRepository: ExploreRepository,
    private val syncStatus: ExploreDataSyncStatus,
    private val exploreConfig: ExploreConfig,
    private val repositoryFactory: DashSpendRepositoryFactory
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val USE_TEST_DB_KEY = "use_test_database"
        const val DISCONNECTED_PROVIDER_KEY = "disconnected_provider"

        /**
         * How many times to re-run a sync whose merchant catalogue could not be loaded.
         *
         * The first catalogue request after a login is the only chance to fill the list, so a
         * transient failure has to be retried or the user is left with an empty Explore screen
         * and nothing but signing out and back in to fix it. The cap stops a service that is
         * down for the day from retrying on battery forever.
         */
        private const val MAX_CATALOG_ATTEMPTS = 5

        private val ALL_PROVIDERS = listOf(GiftCardProviderType.CTX, GiftCardProviderType.PiggyCards)

        private val log = LoggerFactory.getLogger(ExploreSyncWorker::class.java)

        fun run(@ApplicationContext context: Context, isMainNet: Boolean) {
            val inputData = Data.Builder().putBoolean(USE_TEST_DB_KEY, !isMainNet)
            val syncDataWorkRequest =
                OneTimeWorkRequest.Builder(ExploreSyncWorker::class.java)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .setInputData(inputData.build())
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("Sync Explore Data", ExistingWorkPolicy.KEEP, syncDataWorkRequest)
        }

        /**
         * Rebuilds the merchant list after a gift card account is connected or disconnected.
         *
         * The merchant table is assembled from the services the user is signed in to, so
         * signing in or out changes what the list should contain. Unlike [run] this replaces
         * a sync already in flight: that one was started under the previous auth state and
         * would write the list the user just changed.
         *
         * [disconnected] names the service the user just signed out of, and is the only thing
         * that lets the sync delete that service's merchants. Nothing else can stand in for
         * it: a session check answers false for an unreachable network and an unreadable
         * keystore as readily as for a sign-out, and acting on that answer costs the user
         * their whole list. Pass null when connecting or refreshing.
         */
        fun runForAccountChange(
            @ApplicationContext context: Context,
            disconnected: GiftCardProviderType? = null
        ) {
            val syncDataWorkRequest = OneTimeWorkRequest.Builder(ExploreSyncWorker::class.java)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .setInputData(
                    Data.Builder()
                        .putString(DISCONNECTED_PROVIDER_KEY, disconnected?.name)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("Sync Explore Data", ExistingWorkPolicy.REPLACE, syncDataWorkRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        log.info("sync explore db started")

        var remoteDataTimestamp = 0L
        var preloadedDbTimestamp = 0L
        var catalogSynced = true
        var databasePrefs = exploreConfig.exploreDatabasePrefs.first()

        try {
            syncStatus.setSyncProgress(0.0)

            val timeInMillis = measureTimeMillis {
                val updateFile = exploreRepository.getUpdateFile()
                val checkTestDB = inputData.getBoolean(USE_TEST_DB_KEY, false)
                val hasPreloaded = exploreRepository.preloadFromAssetsInto(updateFile, checkTestDB)

                log.info(
                    "local data timestamp: ${databasePrefs.localDbTimestamp} (${Date(databasePrefs.localDbTimestamp)})"
                )

                remoteDataTimestamp = exploreRepository.getRemoteTimestamp()
                log.info("remote data timestamp: $remoteDataTimestamp (${Date(remoteDataTimestamp)})")

                if (hasPreloaded) {
                    preloadedDbTimestamp = exploreRepository.getTimestamp(updateFile)
                    log.info("preloaded data timestamp: $preloadedDbTimestamp (${Date(preloadedDbTimestamp)})")

                    // Only take the asset path when the asset can actually improve on
                    // local state: fresh install (local == 0) or asset is newer. The
                    // "local doesn't match remote" case is what the server download
                    // below is for — loading a stale asset over a newer local DB just
                    // forces migrations on an old user_version and risks crashing.
                    if (databasePrefs.localDbTimestamp == 0L ||
                        databasePrefs.localDbTimestamp < preloadedDbTimestamp
                    ) {
                        ExploreDatabase.updateDatabase(appContext, exploreRepository, exploreConfig)
                        // Re-read prefs after updateDatabase: finalizeUpdate writes the new
                        // localDbTimestamp asynchronously on configScope, so the snapshot
                        // captured at the start of doWork() is now stale. Saving the stale
                        // copy here would clobber finalizeUpdate's concurrent write.
                        val latestPrefs = exploreConfig.exploreDatabasePrefs.first()
                        databasePrefs = latestPrefs.copy(preloadedOnTimestamp = preloadedDbTimestamp)
                        exploreConfig.saveExploreDatabasePrefs(databasePrefs)
                    }
                }

                if (!updateFile.delete()) {
                    log.error("unable to delete " + updateFile.absolutePath)
                }

                if (databasePrefs.localDbTimestamp >= remoteDataTimestamp) {
                    log.info("explore db is up to date, nothing to sync")
                    syncStatus.setSyncProgress(100.0)
                    databasePrefs = databasePrefs.copy(failedSyncAttempts = 0)

                    if (databasePrefs.lastSyncTimestamp <= 0) {
                        // Some devices might have this as 0 due to the bug. Need to update
                        // manually
                        // TODO: this can be removed after some time
                        databasePrefs = databasePrefs.copy(lastSyncTimestamp = remoteDataTimestamp)
                    }

                    exploreConfig.saveExploreDatabasePrefs(databasePrefs)

                    val synced = syncMerchantCatalog()
                    addPiggyCardsTestMerchantIfNeeded()
                    return@withContext catalogResult(synced)
                }
                syncStatus.setSyncProgress(10.0)

                exploreRepository.download()

                syncStatus.setSyncProgress(80.0)

                ExploreDatabase.updateDatabase(appContext, exploreRepository, exploreConfig)
            }

            log.info("sync explore db finished, took $timeInMillis ms")

            syncStatus.setSyncProgress(100.0)
            catalogSynced = syncMerchantCatalog()
            addPiggyCardsTestMerchantIfNeeded()
        } catch (ex: FireplaceNetworkException) {
            log.warn("sync explore no network", ex)
            syncStatus.setSyncError(ex)
            return@withContext Result.failure()
        } catch (ex: Exception) {
            analytics.logError(
                ex,
                "local: ${databasePrefs.localDbTimestamp}, " +
                    "preloaded: $preloadedDbTimestamp, remote: $remoteDataTimestamp"
            )
            log.error("sync explore db crashed ${ex.message}", ex)
            syncStatus.setSyncError(ex)
            exploreConfig.saveExploreDatabasePrefs(
                databasePrefs.copy(failedSyncAttempts = databasePrefs.failedSyncAttempts + 1)
            )
            return@withContext Result.failure()
        }

        databasePrefs = exploreConfig.exploreDatabasePrefs.first()
        exploreConfig.saveExploreDatabasePrefs(databasePrefs.copy(failedSyncAttempts = 0))
        return@withContext catalogResult(catalogSynced)
    }

    /**
     * Rebuilds the merchant table from the gift card services the user is signed in to.
     *
     * Upstream fills this table from the preloaded `explore.db`, which this build cannot
     * download, so without this step the merchant list is empty no matter how the rest of the
     * sync goes. Failures are swallowed: a merchant list that could not be refreshed is worth
     * less than the rest of the sync, and the existing rows stay usable.
     */
    private suspend fun syncMerchantCatalog(): Boolean {
        return try {
            val database = ExploreDatabase.getAppDatabase(appContext, exploreConfig)
            val sources = listOf(
                CTXMerchantCatalogSource(
                    repositoryFactory.create(GiftCardProviderType.CTX) as CTXSpendRepository
                ),
                PiggyCardsMerchantCatalogSource(
                    repositoryFactory.create(GiftCardProviderType.PiggyCards) as PiggyCardsRepository
                )
            )

            // Matched strictly, never through GiftCardProviderType.fromProviderName: that
            // falls back to CTX for anything it does not recognise, and here an unrecognised
            // name would authorise deleting a service the user never disconnected.
            val disconnected = inputData.getString(DISCONNECTED_PROVIDER_KEY)
                ?.let { name -> ALL_PROVIDERS.firstOrNull { it.name == name } }

            val result = MerchantCatalogSync(
                merchantDao = database.merchantDao(),
                giftCardProviderDao = database.giftCardProviderDao(),
                sources = sources,
                // The test merchants are written by addPiggyCardsTestMerchantIfNeeded() and
                // carry the same source as real PiggyCards rows, so the sync has to be told
                // to leave them alone.
                protectedMerchantIds = PiggyCardsTestMerchants.ALL.map { it.merchantId }.toSet(),
                disconnected = setOfNotNull(disconnected),
                inTransaction = { block -> database.withTransaction(block) }
            ).sync()

            if (result.failedSources.isNotEmpty()) {
                log.warn("merchant catalogue sync could not reach: {}", result.failedSources)
            }

            result.failedSources.isEmpty()
        } catch (ex: Exception) {
            log.error("merchant catalogue sync failed", ex)
            false
        }
    }

    /**
     * Whether a catalogue that could not be loaded is worth another attempt.
     *
     * Returning success here would throw away the backoff the work request is configured with,
     * and nothing else re-runs this: the sync fires on sign-in and on an account change, so a
     * user whose first post-login catalogue request timed out would be left with an empty list
     * until they signed out and back in.
     */
    private fun catalogResult(synced: Boolean): Result = when {
        synced -> Result.success()
        runAttemptCount < MAX_CATALOG_ATTEMPTS -> {
            log.warn("merchant catalogue incomplete, retrying (attempt {})", runAttemptCount + 1)
            Result.retry()
        }
        else -> {
            log.warn("merchant catalogue still incomplete after {} attempts, giving up", runAttemptCount)
            Result.success()
        }
    }

    private suspend fun addPiggyCardsTestMerchantIfNeeded() {
        val database = ExploreDatabase.getAppDatabase(appContext, exploreConfig)
        val merchantDao = database.merchantDao()
        val giftCardProviderDao = database.giftCardProviderDao()

        val testMerchantIds = PiggyCardsTestMerchants.ALL.map { it.merchantId }
        if (merchantDao.getMerchantById(PIGGY_CARDS_TEST_FIXED_MERCHANT_ID) != null) {
            val deletedProviders = giftCardProviderDao.deleteByMerchantIds(testMerchantIds)
            val deletedMerchants = merchantDao.deleteByMerchantIds(testMerchantIds)
            log.info(
                "removed existing PiggyCards test data: $deletedMerchants merchant(s), " +
                    "$deletedProviders provider(s)"
            )
        }

        if (!SUPPORT_PIGGY_CARDS_TEST_MERCHANT) {
            return
        }

        try {
            log.info("adding PiggyCards test merchants")

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

            val merchants = PiggyCardsTestMerchants.ALL.map { data ->
                Merchant(
                    merchantId = data.merchantId,
                    paymentMethod = data.paymentMethod,
                    redeemType = data.redeemType,
                    savingsPercentage = data.providerSavingsPercentage,
                    denominationsType = data.providerDenominationsType,
                    addDate = now,
                    updateDate = now
                ).apply {
                    name = data.name
                    active = true
                    source = data.provider
                    sourceId = data.sourceId
                    logoLocation = data.logoLocation
                    type = data.type
                    territory = data.territory
                    city = data.city
                    website = data.website
                }
            }
            merchantDao.save(merchants)

            PiggyCardsTestMerchants.ALL.forEach { data ->
                giftCardProviderDao.insert(
                    GiftCardProvider(
                        merchantId = data.merchantId,
                        provider = data.provider,
                        redeemType = data.redeemType,
                        savingsPercentage = data.providerSavingsPercentage,
                        active = true,
                        denominationsType = data.providerDenominationsType,
                        sourceId = data.sourceId
                    )
                )
            }

            log.info("PiggyCards test merchant added successfully")
        } catch (ex: Exception) {
            log.error("error adding PiggyCards test merchant", ex)
        }
    }
}
