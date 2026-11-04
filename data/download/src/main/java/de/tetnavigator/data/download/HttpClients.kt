package de.tetnavigator.data.download

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Der einzige HTTP-Client der App. */
internal object HttpClients {
    fun create(policy: NetworkPolicy): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(OfflineGuardInterceptor(policy))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
}
