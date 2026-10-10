package com.univpn.app.server

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * One-time PIN for the web importer. The PIN is shown on the TV, so only someone who can see the
 * screen can write configs or credentials. After [maxFailures] wrong guesses the guard locks for
 * [lockMs], which keeps brute-forcing a 6-digit PIN impractical.
 */
class PinGuard(
    val pin: String = newPin(),
    private val maxFailures: Int = 5,
    private val lockMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Result { OK, WRONG, LOCKED }

    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun check(given: String?): Result {
        val now = clock()
        if (now < lockedUntil) return Result.LOCKED
        val ok = given != null &&
            MessageDigest.isEqual(given.trim().toByteArray(), pin.toByteArray())
        if (ok) {
            failures = 0
            return Result.OK
        }
        if (++failures >= maxFailures) {
            failures = 0
            lockedUntil = now + lockMs
        }
        return Result.WRONG
    }

    companion object {
        fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
    }
}
