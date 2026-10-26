package com.takaotech.departureboard.wear.net

import com.takaotech.departureboard.watch.WatchOffline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchNetworkTest {

    @Test
    fun `senza Wi-Fi né LTE non c'è rete`() {
        assertEquals(
            WatchOffline.NoNetwork,
            networkVerdict(found = false, validated = false, captivePortal = false, probeOk = null),
        )
    }

    @Test
    fun `una rete non validata non porta a internet`() {
        assertEquals(
            WatchOffline.NoInternet,
            networkVerdict(found = true, validated = false, captivePortal = false, probeOk = null),
        )
    }

    @Test
    fun `una rete con portale di accesso non porta a internet`() {
        assertEquals(
            WatchOffline.NoInternet,
            networkVerdict(found = true, validated = true, captivePortal = true, probeOk = true),
        )
    }

    @Test
    fun `validata ma la prova fallisce (timeout, DNS) non porta a internet`() {
        assertEquals(
            WatchOffline.NoInternet,
            networkVerdict(found = true, validated = true, captivePortal = false, probeOk = false),
        )
        assertEquals(
            WatchOffline.NoInternet,
            networkVerdict(found = true, validated = true, captivePortal = false, probeOk = null),
        )
    }

    @Test
    fun `validata e la prova risponde - si scarica`() {
        assertNull(networkVerdict(found = true, validated = true, captivePortal = false, probeOk = true))
    }
}
