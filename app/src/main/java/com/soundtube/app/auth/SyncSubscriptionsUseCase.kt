package com.soundtube.app.auth

import com.soundtube.app.data.local.SubscriptionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase to synchronize remote YouTube subscriptions into the local Room database.
 */
class SyncSubscriptionsUseCase(
    private val authStorage: EncryptedAuthStorage,
    private val innerTubeClient: YouTubeInnerTubeClient,
    private val subscriptionDao: SubscriptionDao
) {

    /**
     * Executes synchronization.
     * @return Result containing the count of successfully ingested subscriptions.
     */
    suspend fun execute(): Result<Int> = withContext(Dispatchers.IO) {
        if (!authStorage.isLoggedIn()) {
            return@withContext Result.failure(
                IllegalStateException("لم يتم تسجيل الدخول بحساب Google / YouTube بعد. يرجى تسجيل الدخول أولاً.")
            )
        }

        val cookies = authStorage.getCookies()
        val sapisid = authStorage.getSapisid()

        if (cookies.isNullOrBlank() || sapisid.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("بيانات الجلسة (SAPISID) غير متوفرة أو منتهية الصلاحية.")
            )
        }

        val fetchResult = innerTubeClient.fetchAllSubscriptions(
            cookieHeader = cookies,
            sapisid = sapisid
        )

        fetchResult.fold(
            onSuccess = { subscriptions ->
                if (subscriptions.isNotEmpty()) {
                    subscriptionDao.insertAll(subscriptions)
                    Result.success(subscriptions.size)
                } else {
                    val hasLoginInfo = authStorage.hasLoginInfo()
                    val errorMessage = if (!hasLoginInfo) {
                        "انتهت صلاحية جلسة تسجيل الدخول إلى YouTube (LOGIN_INFO غير متوفر). يرجى الضغط على تسجيل الخروج ثم تسجيل الدخول مرة أخرى."
                    } else {
                        "لم يتم العثور على أي قنوات مشتركة في حسابك الحالي. إذا كانت لديك قنوات على حساب آخر أو ملف CSV، يمكنك استيرادها من خيار Google Takeout بالأسفل."
                    }
                    Result.failure(IllegalStateException(errorMessage))
                }
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }
}
