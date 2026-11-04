package com.minwook.pleoswork

import java.util.UUID

data class DriverProfile(val id: String, val name: String, val carrier: String, val mode: ServiceMode, val assignment: VehicleSite?, val refrigerated: Boolean = false, val dispatch: DriverDispatch? = null)

private val towerSite = VehicleSite(
    "서린타워", "서문 · 지상 하역장", 660, 4100,
    listOf(SiteEntrance("서문 · 하역장", 350, "서문 진입 후 관리실 방향"), SiteEntrance("고객 주차장", 220, "지하주차장 출입구")),
    listOf(SiteBay("서문 하역구역", "후면 하차", "보행 출입구 앞 공간을 비워두세요"), SiteBay("관리실 옆", "우측 하차", "관리실 옆 지정 구역")),
    "서문 대기구역",
)

/** Demo identities only. A production adapter must supply an authenticated driver and their dispatch. */
val demoDrivers = listOf(
    DriverProfile("parcel-east", "김민준", "동부택배", ServiceMode.PARCEL, parcelSite, dispatch = parcelEastDispatch),
    DriverProfile("parcel-west", "이서연", "서부택배", ServiceMode.PARCEL, towerSite, dispatch = parcelWestDispatch),
    DriverProfile("freight-cold", "박지호", "중앙운송", ServiceMode.FREIGHT, freightSite, refrigerated = true, dispatch = coldFreightDispatch),
    DriverProfile("no-dispatch", "최하늘", "동부택배", ServiceMode.PARCEL, null),
)

enum class LinkPhase { WAITING, APPROVAL, LOADING, ACTIVE, EXPIRED, FAILED }

data class LinkChallenge(val id: String, val expiresAt: Long) {
    val payload: String get() = "pleoswork-demo://pair/v1/$id"
    fun valid(now: Long) = now < expiresAt
    companion object {
        fun create(now: Long) = LinkChallenge(UUID.randomUUID().toString(), now + 300_000)
    }
}

data class DriverLink(
    val challenge: LinkChallenge? = null,
    val phase: LinkPhase = LinkPhase.WAITING,
    val pendingId: String? = null,
    val activeId: String? = null,
    val failNextLoad: Boolean = false,
) {
    val driver: DriverProfile? get() = if (phase == LinkPhase.ACTIVE) demoDrivers.find { it.id == activeId } else null
    val pending: DriverProfile? get() = demoDrivers.find { it.id == pendingId }
}

/** Local pairing simulation. Tokens never contain driver names, assignments or real credentials. */
object DriverLinkReducer {
    fun renew(challenge: LinkChallenge) = DriverLink(challenge = challenge)
    fun expire(s: DriverLink, now: Long): DriverLink =
        if (s.phase in setOf(LinkPhase.WAITING, LinkPhase.APPROVAL) && s.challenge?.valid(now) == false)
            s.copy(phase = LinkPhase.EXPIRED, pendingId = null) else s
    fun scan(s: DriverLink, payload: String, driverId: String, now: Long, parked: Boolean, failLoad: Boolean = false): DriverLink {
        if (!parked || s.phase != LinkPhase.WAITING || s.challenge?.payload != payload || demoDrivers.none { it.id == driverId }) return s
        val checked = expire(s, now)
        return if (checked.phase == LinkPhase.EXPIRED) checked else checked.copy(phase = LinkPhase.APPROVAL, pendingId = driverId, failNextLoad = failLoad)
    }
    fun approve(s: DriverLink, now: Long, parked: Boolean): DriverLink {
        if (!parked || s.phase != LinkPhase.APPROVAL || s.pending == null) return s
        val checked = expire(s, now)
        return if (checked.phase == LinkPhase.EXPIRED) checked else checked.copy(phase = LinkPhase.LOADING)
    }
    fun finish(s: DriverLink, challengeId: String, parked: Boolean): DriverLink {
        if (!parked || s.phase != LinkPhase.LOADING || s.challenge?.id != challengeId || s.pending == null) return s
        return if (s.failNextLoad) s.copy(phase = LinkPhase.FAILED, failNextLoad = false)
        else s.copy(phase = LinkPhase.ACTIVE, activeId = s.pendingId, pendingId = null)
    }
    fun retry(s: DriverLink, parked: Boolean): DriverLink =
        if (!parked || s.phase != LinkPhase.FAILED || s.pending == null) s else s.copy(phase = LinkPhase.LOADING)
}
