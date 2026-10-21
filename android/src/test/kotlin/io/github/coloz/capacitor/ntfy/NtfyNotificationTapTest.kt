package io.github.coloz.capacitor.ntfy

import org.junit.Assert.*
import org.junit.Test

class NtfyNotificationTapTest {
    @Test fun retainsOnlyLatestTapAndConsumesOnce() {
        val tap = NtfyNotificationTap()
        val first = NtfyNotificationTap.uri("server|topic", "topic", "first")
        val second = NtfyNotificationTap.uri("server|topic", "topic", "second")
        assertTrue(tap.capture(first))
        assertTrue(tap.capture(second))
        assertEquals(second, tap.take())
        assertNull(tap.take())
        tap.capture(first)
        tap.clear()
        assertNull(tap.take())
    }

    @Test fun referenceIsBoundToSubscriptionAndTransportIdentity() {
        val uri = NtfyNotificationTap.uri("one", "topic", "id")
        assertEquals(uri, NtfyNotificationTap.uri("one", "topic", "id"))
        assertNotEquals(uri, NtfyNotificationTap.uri("two", "topic", "id"))
        assertNotEquals(uri, NtfyNotificationTap.uri("one", "other", "id"))
        assertNotEquals(NtfyNotificationTap.uri("ab", "c", "d"), NtfyNotificationTap.uri("a", "bc", "d"))
    }

    @Test fun arbitraryUrlsAndPayloadsCannotCreateAnAction() {
        val tap = NtfyNotificationTap()
        for (uri in listOf(null, "https://example.com", "diandeng://share/abc",
            "capacitor-ntfy://notification/" + "a".repeat(64) + "?message=evil",
            "capacitor-ntfy://notification/../" + "a".repeat(64))) assertFalse(tap.capture(uri))
        assertNull(tap.take())
    }
}
