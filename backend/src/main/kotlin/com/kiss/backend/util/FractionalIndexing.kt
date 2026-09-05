package com.kiss.backend.util

import java.math.BigInteger

/**
 * Fractional indexing for collection ordering.
 *
 * Keys are fixed-width base-62 strings that order lexicographically exactly
 * like their numeric value, so a plain `TEXT` column + `<`/`>` comparisons
 * preserve order without app-side decoding.
 *
 * - [between]/[after] return `null` only when the representable key space
 *   between the bounds is exhausted (practically never: granularity is
 *   62^[WIDTH]); callers then rebalance the collection.
 * - The canonical variable-length fractional-indexing scheme (Figma) trades
 *   key length for unbounded insertions; this fixed-width variant keeps keys
 *   bounded and comparisons trivial, at the cost of a (never-hit) rebalance.
 */
object FractionalIndexing {

    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62
    private const val WIDTH = 20

    private val MAX_VALUE: BigInteger = BigInteger.valueOf(BASE.toLong()).pow(WIDTH)

    /** The very first key. */
    fun start(): String = "0".repeat(WIDTH)

    /** Key strictly greater than [order] (or [start] when null), or null if exhausted. */
    fun after(order: String?): String? {
        if (order == null) return start()
        val value = decode(order)
        val next = value + BigInteger.ONE
        return if (next >= MAX_VALUE) null else encode(next)
    }

    /** Key strictly between [before] and [after] (either may be null), or null if exhausted. */
    fun between(before: String?, after: String?): String? {
        val lo = before?.let(::decode) ?: BigInteger.ZERO
        val hi = after?.let(::decode) ?: MAX_VALUE.subtract(BigInteger.ONE)
        if (lo >= hi) return null
        val mid = lo.add(hi).shiftRight(1)
        return if (mid <= lo || mid >= hi) null else encode(mid)
    }

    private fun encode(value: BigInteger): String {
        var v = value
        var digits = ""
        do {
            val d = v.mod(BigInteger.valueOf(BASE.toLong())).toInt()
            digits = DIGITS[d] + digits
            v = v.divide(BigInteger.valueOf(BASE.toLong()))
        } while (v > BigInteger.ZERO)
        return digits.padStart(WIDTH, '0')
    }

    private fun decode(key: String): BigInteger {
        require(key.length == WIDTH) { "Invalid key length: $key" }
        require(key.all { DIGITS.indexOf(it) >= 0 }) { "Invalid key digits: $key" }
        var value = BigInteger.ZERO
        key.forEach { c ->
            value = value.multiply(BigInteger.valueOf(BASE.toLong()))
                .add(BigInteger.valueOf(DIGITS.indexOf(c).toLong()))
        }
        return value
    }
}