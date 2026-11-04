package com.minwook.pleoswork

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class DriverLinkTest {
    private val challenge = LinkChallenge("6b9edf85-56d8-4b71-9c20-c953569fbad8", 300_000)
    private fun waiting() = DriverLinkReducer.renew(challenge)
    private fun scanned(fail: Boolean = false) = DriverLinkReducer.scan(waiting(), challenge.payload, "freight-cold", 1000, true, fail)
    private fun loading(fail: Boolean = false) = DriverLinkReducer.approve(scanned(fail), 2000, true)

    @Test fun identityAndAssignmentStayHiddenUntilApprovalAndImportFinish() {
        assertNull(waiting().driver)
        assertNull(scanned().driver)
        assertNull(loading().driver)
        val active = DriverLinkReducer.finish(loading(), challenge.id, true)
        assertEquals(LinkPhase.ACTIVE, active.phase)
        assertEquals("박지호", active.driver?.name)
        assertEquals(freightSite, active.driver?.assignment)
    }
    @Test fun onlyCurrentQrAndRecognizedDemoIdentityCanStartPairing() {
        val s = waiting()
        assertEquals(s, DriverLinkReducer.scan(s, "pleoswork-demo://pair/v1/old", "parcel-east", 1000, true))
        assertEquals(s, DriverLinkReducer.scan(s, challenge.payload, "unknown", 1000, true))
        assertEquals(s, DriverLinkReducer.scan(s, challenge.payload, "parcel-east", 1000, false))
    }
    @Test fun scanDoesNotLetASecondIdentityReplacePendingDriver() {
        val s = scanned()
        assertEquals(s, DriverLinkReducer.scan(s, challenge.payload, "parcel-east", 2000, true))
    }
    @Test fun expiredQrCannotBeScannedOrApproved() {
        assertEquals(LinkPhase.EXPIRED, DriverLinkReducer.scan(waiting(), challenge.payload, "parcel-east", 300_000, true).phase)
        val expired = DriverLinkReducer.approve(scanned(), 300_000, true)
        assertEquals(LinkPhase.EXPIRED, expired.phase)
        assertNull(expired.driver)
        assertNull(expired.pending)
    }
    @Test fun oldQrAndDelayedImportCannotAttachToRegeneratedSession() {
        val fresh = DriverLinkReducer.renew(LinkChallenge("new-challenge", 400_000))
        assertEquals(fresh, DriverLinkReducer.scan(fresh, challenge.payload, "parcel-east", 1000, true))
        assertEquals(fresh, DriverLinkReducer.finish(fresh, challenge.id, true))
        assertEquals(loading(), DriverLinkReducer.finish(loading(), "different-challenge", true))
    }
    @Test fun movingVehicleCannotApproveOrApplyPendingConnection() {
        assertEquals(scanned(), DriverLinkReducer.approve(scanned(), 2000, false))
        assertEquals(loading(), DriverLinkReducer.finish(loading(), challenge.id, false))
    }
    @Test fun importFailureShowsNoAssignmentAndRetryKeepsApprovedIdentity() {
        val failed = DriverLinkReducer.finish(loading(true), challenge.id, true)
        assertEquals(LinkPhase.FAILED, failed.phase)
        assertNull(failed.driver)
        assertEquals("freight-cold", failed.pendingId)
        val done = DriverLinkReducer.finish(DriverLinkReducer.retry(failed, true), challenge.id, true)
        assertEquals("freight-cold", done.driver?.id)
    }
    @Test fun completedQrCannotBeReusedAndNewShiftHasNoPreviousDriver() {
        val active = DriverLinkReducer.finish(loading(), challenge.id, true)
        assertEquals(active, DriverLinkReducer.scan(active, challenge.payload, "parcel-east", 3000, true))
        val next = DriverLinkReducer.renew(LinkChallenge("next-shift", 400_000))
        assertNull(next.driver)
        assertNull(next.pending)
        assertEquals(LinkPhase.WAITING, next.phase)
    }
    @Test fun emptyDispatchConnectsWithoutBorrowingAnotherDriversDestination() {
        val scan = DriverLinkReducer.scan(waiting(), challenge.payload, "no-dispatch", 1000, true)
        val done = DriverLinkReducer.finish(DriverLinkReducer.approve(scan, 2000, true), challenge.id, true)
        assertNotNull(done.driver)
        assertNull(done.driver?.assignment)
    }
    @Test fun qrCanBeDecodedAndContainsNoDriverOrDispatchData() {
        val matrix = pairingQr(challenge.payload)
        val scale = 8
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { index -> if (matrix[(index % side) / scale, (index / side) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))
        assertEquals(challenge.payload, decoded.text)
        demoDrivers.forEach { assertFalse(decoded.text.contains(it.name)); assertFalse(decoded.text.contains(it.id)) }
    }
}
