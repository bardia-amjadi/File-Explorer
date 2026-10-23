package com.bardia.fileexplorer.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /**
     * Streaming SHA-256: constant memory regardless of file size, cancellable between chunks.
     * [onBytes] receives the number of bytes processed per chunk.
     */
    suspend fun sha256(input: InputStream, onBytes: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            onBytes(n.toLong())
            currentCoroutineContext().ensureActive()
        }
        return toHex(digest.digest())
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
