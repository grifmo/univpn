package com.univpn.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGuardTest {

    private var now = 1_000L
    private fun guard() = PinGuard(pin = "123456", maxFailures = 3, lockMs = 60_000L, clock = { now })

    @Test
    fun correctPin_isAccepted_withWhitespace() {
        assertEquals(PinGuard.Result.OK, guard().check(" 123456 "))
    }

    @Test
    fun missingOrWrongPin_isRejected() {
        val g = guard()
        assertEquals(PinGuard.Result.WRONG, g.check(null))
        assertEquals(PinGuard.Result.WRONG, g.check("654321"))
    }

    @Test
    fun repeatedWrongGuesses_lockEvenTheRightPin() {
        val g = guard()
        repeat(3) { g.check("000000") }
        assertEquals(PinGuard.Result.LOCKED, g.check("123456"))
        now += 59_999L
        assertEquals(PinGuard.Result.LOCKED, g.check("123456"))
        now += 1L
        assertEquals(PinGuard.Result.OK, g.check("123456"))
    }

    @Test
    fun correctPin_resetsFailureCount() {
        val g = guard()
        repeat(2) { g.check("000000") }
        assertEquals(PinGuard.Result.OK, g.check("123456"))
        repeat(2) { g.check("000000") }
        assertEquals(PinGuard.Result.OK, g.check("123456"))
    }

    @Test
    fun newPin_isSixDigits() {
        repeat(100) { assertTrue(PinGuard.newPin().matches(Regex("\\d{6}"))) }
    }
}
