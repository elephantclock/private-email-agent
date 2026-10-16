package tech.elephantclock.privatemail.core.mail.autoconfig

import android.content.Context
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Raw DNS queries; returns the response message, or null on failure or timeout. */
fun interface DnsClient {
    fun query(
        name: String,
        type: Int,
    ): ByteArray?
}

/** Uses the system resolver (Private DNS included) via [DnsResolver], API 29+. */
class AndroidDnsClient(
    context: Context,
    private val timeoutMillis: Long = 5_000,
) : DnsClient {
    private val executor = Executors.newSingleThreadExecutor()

    private val resolver: DnsResolver =
        if (Build.VERSION.SDK_INT >= 37) {
            DnsResolver(context.applicationContext, Looper.getMainLooper())
        } else {
            @Suppress("DEPRECATION")
            DnsResolver.getInstance()
        }

    override fun query(
        name: String,
        type: Int,
    ): ByteArray? {
        val result = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        val cancel = CancellationSignal()
        resolver.rawQuery(
            null,
            name,
            DnsResolver.CLASS_IN,
            type,
            DnsResolver.FLAG_EMPTY,
            executor,
            cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(
                    answer: ByteArray,
                    rcode: Int,
                ) {
                    result.set(answer)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) = done.countDown()
            },
        )
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) cancel.cancel()
        return result.get()
    }
}
