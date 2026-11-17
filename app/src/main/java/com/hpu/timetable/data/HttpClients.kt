package com.hpu.timetable.data

object HttpClients {
    val base: okhttp3.OkHttpClient by lazy { okhttp3.OkHttpClient.Builder().build() }
}

/** 协程取消不应被转换成业务错误，更不应触发自动重登。 */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
