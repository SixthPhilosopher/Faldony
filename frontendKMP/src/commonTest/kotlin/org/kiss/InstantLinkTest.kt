package org.kiss

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Node smoke test for kotlinx-datetime IR linkage on the js target — the
 * previous 0.6.2 build crashed with IrLinkageError at runtime, so instantiate
 * the real symbols here to catch it during `jsTest` instead of in the browser.
 */
class InstantLinkTest {

    @Test
    fun clockSystemAndInstantLink() {
        val now = Clock.System.now()
        val back = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - 86_400_000)
        val dt = back.toLocalDateTime(TimeZone.currentSystemDefault())
        assertTrue(dt.year >= 2000, "expected a sane local date, got $dt")
    }
}