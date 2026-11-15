package com.minwook.pleoswork

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Real service clients. Missing/denied values remain unknown; there is no fake success fallback. */
class PleosGateway(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(VehicleState())
    val state = mutable.asStateFlow()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var active = false
    private var generation = 0
    private var acceptingRouteEvents = false
    private var routeDeadline: Runnable? = null
    private var lastNavigationQuery = 0L
    private val refreshTask = object : Runnable {
        override fun run() {
            if (!active) return
            refreshNavigation()
            refreshDoor()
            refreshEnergy()
            main.postDelayed(this, 20_000)
        }
    }

    private fun error(source: String, e: Throwable) {
        Log.w("PleosWork", source, e)
        mutable.update { it.copy(errors = (listOf("$source · ${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}") + it.errors).distinct().take(12)) }
    }

    private val propertyListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (!active) return
            val valid = value.status == CarPropertyValue.STATUS_AVAILABLE
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> mutable.update { it.copy(speedKph = if (valid) (value.value as? Float)?.times(3.6f) else null, speedObservedAt = System.currentTimeMillis()) }
                VehiclePropertyIds.GEAR_SELECTION -> mutable.update { it.copy(gear = if (valid) value.value as? Int else null) }
                VehiclePropertyIds.PARKING_BRAKE_ON -> {
                    mutable.update { it.copy(parkingBrake = if (valid) value.value as? Boolean else null) }
                    Log.i("PleosWork", "Parking brake=${state.value.parkingBrake}")
                }
            }
        }
        override fun onErrorEvent(propId: Int, areaId: Int) {
            if (!active) return
            when (propId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> mutable.update { it.copy(speedKph = null, speedObservedAt = System.currentTimeMillis()) }
                VehiclePropertyIds.GEAR_SELECTION -> mutable.update { it.copy(gear = null) }
                VehiclePropertyIds.PARKING_BRAKE_ON -> mutable.update { it.copy(parkingBrake = null) }
            }
        }
    }

    private val naviEvents = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) {
            if (!active) return
            val valid = info.name.isNotBlank() && info.latitude.isFinite() && info.longitude.isFinite() && info.latitude in -90.0..90.0 && info.longitude in -180.0..180.0 && info.remainTime >= 0 && info.remainDistance >= 0
            val observedAt = System.currentTimeMillis()
            mutable.update { it.copy(navigationObservedAt = observedAt,
                navigation = if(valid) NavReading(NavTarget(info.name,info.latitude,info.longitude,info.address,info.poiId,info.poiSubId),info.remainTime,info.remainDistance,observedAt) else null) }
            Log.i("PleosWork", "DestinationInfo received: valid=$valid, seconds=${info.remainTime}, meters=${info.remainDistance}")
            if (context.packageName.endsWith(".qa")) Log.d("WorkNavigationQA", "Destination ${info.name} ${info.latitude},${info.longitude} poi=${info.poiId}")
        }
        override fun onDrivingInfo(info: DrivingInfo) {
            if (!active || info.destination.duration < 0) return
            // Refresh the complete destination. DrivingState.distance is not remaining distance.
            if (System.currentTimeMillis() - lastNavigationQuery >= 2000) refreshNavigation()
        }
        override fun onDestinationChanged() { if(active) { mutable.update { it.copy(navigation=null, navigationArrival=null) }; refreshNavigation() } }
        override fun onRouteCancelled() {
            if(active) mutable.update { it.copy(navigation=null, navigationArrival=null, routeStatus="cancelled", routeMessage="경로가 취소되었습니다", routeEventAt=System.currentTimeMillis()) }
            Log.i("PleosWork", "Native route cancelled event")
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            if (!active) return
            val arrival = navigationArrival(state.value.navigation, info.name, info.address, System.currentTimeMillis())
            mutable.update { it.copy(navigationArrival = arrival) }
            Log.i("PleosWork", "Native arrival received; matched recent destination=${arrival != null}")
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            if (active) mutable.update { it.copy(routeStatus = "routing", routeMessage = "경로 탐색 중", navigationArrival = null, routeEventAt=System.currentTimeMillis()) }
        }
        override fun onRoutingCompleted() {
            if (!active) return
            routeDeadline?.let(main::removeCallbacks)
            mutable.update { it.copy(routeStatus = "ready", routeMessage = "경로 탐색 완료", routeEventAt=System.currentTimeMillis()) }
            refreshNavigation()
            Log.i("PleosWork", "NaviHelper onRoutingCompleted")
        }
        override fun onError(errorCode: NaviErrorCode) {
            if (!active) return
            acceptingRouteEvents = false
            routeDeadline?.let(main::removeCallbacks)
            mutable.update { it.copy(routeStatus = "error", routeMessage = "내비게이션 응답: $errorCode", routeEventAt=System.currentTimeMillis(), navigation=null, navigationArrival=null,
                navigationScreenMessage = "내비 응답을 확인해 주세요 · $errorCode") }
            Log.w("PleosWork", "NaviHelper onError: $errorCode")
        }
    }

    fun start() {
        if (active) return
        active = true
        val session = ++generation
        val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        mutable.update { it.copy(automotive = automotive, uxRestricted = null, gear = null, speedKph = null, parkingBrake = null, doorPosition = null) }
        if (!automotive) {
            mutable.update { it.copy(vehicleStatus = "Pleos 환경 필요", navigationStatus = "Pleos 환경 필요") }
            return
        }
        runCatching {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!active || session != generation) return@createCar
                if (!ready) {
                    mutable.update { it.copy(uxRestricted = null, gear = null, speedKph = null, parkingBrake = null, doorPosition = null,
                        fuelPercent = null, batteryPercent = null, rangeKm = null, vehicleStatus = "차량 연결 끊김") }
                    return@createCar
                }
                runCatching {
                    ux = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    mutable.update { it.copy(uxRestricted = ux!!.currentCarUxRestrictions.isRequiresDistractionOptimization) }
                    ux!!.registerListener { restrictions ->
                        if (active && session == generation) mutable.update { it.copy(uxRestricted = restrictions.isRequiresDistractionOptimization) }
                    }
                }.onFailure { error("CarUxRestrictions", it) }
                runCatching {
                    properties = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                    for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PARKING_BRAKE_ON)) {
                        runCatching {
                            check(properties!!.getCarPropertyConfig(id) != null) { "Property unavailable: $id" }
                            check(properties!!.registerCallback(propertyListener, id, if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED) CarPropertyManager.SENSOR_RATE_NORMAL else CarPropertyManager.SENSOR_RATE_ONCHANGE)) { "Subscription unavailable: $id" }
                        }.onFailure { error("차량 상태", it) }
                    }
                    refreshEnergy()
                }.onFailure { error("AAOS", it) }
            }
        }.onFailure { error("Car 연결", it) }

        runCatching {
            vehicle = Vehicle(context).also { it.initialize() }
            refreshDoor()
            refreshEnergy()
        }.onFailure {
            mutable.update { s -> s.copy(vehicleStatus = "연결 실패") }
            error("Vehicle SDK", it)
        }
        runCatching {
            navi = NaviHelper(context)
            navi!!.initialize(onConnected = {
                if (active && session == generation) {
                    navi?.addListener(naviEvents)
                    mutable.update { it.copy(navigationStatus = "연결됨") }
                    Log.i("PleosWork", "NaviHelper connected")
                    refreshNavigation()
                }
            }, onFailed = { e ->
                if (active && session == generation) {
                    mutable.update { it.copy(navigationStatus = "연결 실패") }
                    error("NaviHelper", e)
                }
            })
        }.onFailure { error("NaviHelper 초기화", it) }
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, 20_000)
    }

    fun refreshNavigation() {
        if (!active || state.value.navigationStatus != "연결됨") return
        lastNavigationQuery = System.currentTimeMillis()
        runCatching { navi?.getDestinationInfo() }.onFailure { error("목적지 조회",it); mutable.update { s -> s.copy(navigation=null) } }
    }

    /** Public screen handoff only; Pleos owns the map and the window arrangement. */
    fun openNavigation(): Boolean {
        if (!state.value.canWork) return false
        val helper = navi
        if (!active || helper == null || state.value.navigationStatus != "연결됨") {
            mutable.update { it.copy(navigationScreenMessage = "기본 내비 연결을 기다리고 있습니다") }
            return false
        }
        return runCatching {
            // The visible activity already owns this handoff. Leave task ordering
            // to Pleos when navigation takes focus.
            helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
        }
            .onSuccess {
                mutable.update { it.copy(navigationScreenMessage = "") }
                Log.i("PleosWork", "NaviHelper Foreground screen request sent")
            }.onFailure { failure ->
                mutable.update { it.copy(navigationScreenMessage = "기본 내비를 열지 못했습니다. 다시 시도해 주세요") }
                error("Navigation screen", failure)
            }.isSuccess // Request delivery, not confirmation of the OS window arrangement.
    }

    fun refreshEnergy() {
        if (!active) return
        val session=generation
        val v=vehicle
        fun current()=active && generation==session
        fun validPercent(value: Int?)=value?.takeIf { it in 0..100 }
        if(v != null && context.checkSelfPermission("pleos.car.permission.CAR_ENERGY") == PackageManager.PERMISSION_GRANTED) runCatching {
            v.getFuel().checkFuelPercentCapabilities(onComplete={ config ->
                if(current()) {
                    if(config.accessType in setOf(AccessType.READ,AccessType.READ_WRITE)) v.getFuel().getFuelPercent(onComplete={ value ->
                        if(current()) { mutable.update { it.copy(fuelPercent=validPercent(value),fuelUpdatedAt=System.currentTimeMillis(),energyStatus="API 수신") }; Log.i("PleosWork","Fuel percent=$value") }
                    },onFailed={ e -> if(current()) { mutable.update { it.copy(fuelPercent=null) }; error("연료 조회",e) } })
                    else mutable.update { it.copy(fuelPercent=null) }
                }
            },onFailed={ e -> if(current()) { mutable.update { it.copy(fuelPercent=null) }; error("연료 지원 확인",e) } })
            v.getEvBattery().checkEvBatteryStateOfChargeCapabilities(onComplete={ config ->
                if(current()) {
                    if(config.accessType in setOf(AccessType.READ,AccessType.READ_WRITE)) v.getEvBattery().getEvBatteryStateOfCharge(onComplete={ value ->
                        if(current()) { mutable.update { it.copy(batteryPercent=validPercent(value),batteryUpdatedAt=System.currentTimeMillis(),energyStatus="API 수신") }; Log.i("PleosWork","Battery percent=$value") }
                    },onFailed={ e -> if(current()) { mutable.update { it.copy(batteryPercent=null) }; error("배터리 조회",e) } })
                    else mutable.update { it.copy(batteryPercent=null) }
                }
            },onFailed={ e -> if(current()) { mutable.update { it.copy(batteryPercent=null) }; error("배터리 지원 확인",e) } })
        }.onFailure { error("에너지 조회",it) }
        if(context.checkSelfPermission(Car.PERMISSION_ENERGY) != PackageManager.PERMISSION_GRANTED) {
            mutable.update { it.copy(rangeKm = null, rangeUpdatedAt = 0) }
            return
        }
        runCatching {
            val manager=properties ?: return@runCatching
            val id=VehiclePropertyIds.RANGE_REMAINING
            val value=if(manager.getCarPropertyConfig(id)!=null) manager.getProperty<Float>(id,0) else null
            val km=if(value?.status==CarPropertyValue.STATUS_AVAILABLE) value.value?.toDouble()?.takeIf { it.isFinite() && it>=0 }?.div(1000.0) else null
            mutable.update { it.copy(rangeKm=km,rangeUpdatedAt=System.currentTimeMillis(),energyStatus=if(km==null) "일부 값 미지원" else "API 수신") }
            Log.i("PleosWork","AAOS range km=$km")
        }.onFailure { mutable.update { s -> s.copy(rangeKm=null) }; error("주행 가능 거리",it) }
    }

    fun refreshDoor() {
        val session = generation
        runCatching {
            vehicle?.getDoor()?.getCurrentDoorPosition(
                area = DoorArea(DoorValue.ROW_1_LEFT),
                onComplete = { position: Int? ->
                    if (active && session == generation) {
                        mutable.update { it.copy(doorPosition = position, vehicleStatus = if (position == null) "값 없음" else "연결됨") }
                        Log.i("PleosWork", "Vehicle door position=$position")
                    }
                },
                onFailed = { e: Exception ->
                    if (active && session == generation) {
                        mutable.update { it.copy(doorPosition = null, vehicleStatus = "조회 실패") }
                        error("Door API", e)
                    }
                },
            )
        }.onFailure { mutable.update { s -> s.copy(doorPosition = null) }; error("Door API", it) }
    }

    /** Uses the real destination in Pleos's published example, never fictional shipment coordinates. */
    fun requestSampleRoute() {
        requestDestination(nativeSampleTargets.first())
    }

    /** True means dispatched to NaviHelper, not a successful route calculation. */
    fun requestDestination(target: NavTarget): Boolean {
        if (!active || !target.valid() || !state.value.canWork || state.value.navigationStatus != "연결됨" || navi == null) return false
        acceptingRouteEvents = true
        mutable.update { it.copy(routeStatus = "requested", routeMessage = "내비게이션에 요청 중", routeEventAt=System.currentTimeMillis(), navigation = null, navigationArrival = null) }
        return runCatching {
            navi!!.requestRoute(RouteInfo(
                latitude = target.latitude, longitude = target.longitude,
                displayLongitude = null, displayLatitude = null,
                poiName = target.name, poiId = target.poiId, poiSubId = target.poiSubId ?: "0",
                address = target.address,
            ))
            routeDeadline?.let(main::removeCallbacks)
            routeDeadline = Runnable {
                if (active && acceptingRouteEvents && state.value.routeStatus in setOf("requested", "routing")) {
                    acceptingRouteEvents = false
                    mutable.update { it.copy(routeStatus = "timeout", routeMessage = "내비게이션 응답을 확인하지 못했습니다") }
                }
            }.also { main.postDelayed(it, 30000) }
            Log.i("PleosWork", "Visit destination request dispatched")
            true
        }.onFailure {
            acceptingRouteEvents = false
            mutable.update { s -> s.copy(routeStatus = "error", routeMessage = "경로 요청 실패") }
            error("requestRoute", it)
        }.getOrDefault(false)
    }

    fun cancelRoute() {
        if (!state.value.canWork) return
        acceptingRouteEvents = false
        routeDeadline?.let(main::removeCallbacks)
        runCatching { navi?.cancelRoute() }
            .onSuccess { mutable.update { it.copy(routeStatus = "cancelled", routeMessage = "취소 요청을 전달했습니다", navigation=null) } }
            .onFailure { error("cancelRoute", it) }
    }

    fun stop() {
        active = false
        main.removeCallbacks(refreshTask)
        generation++
        acceptingRouteEvents = false
        routeDeadline?.let(main::removeCallbacks)
        runCatching { ux?.unregisterListener() }
        runCatching { properties?.unregisterCallback(propertyListener) }
        runCatching { navi?.removeListener(naviEvents); navi?.release() }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        ux = null; properties = null; navi = null; vehicle = null; car = null
        mutable.update { it.copy(uxRestricted = null, gear = null, speedKph = null, parkingBrake = null, doorPosition = null, navigation=null, navigationObservedAt=0, navigationArrival=null, fuelPercent=null, batteryPercent=null, rangeKm=null, fuelUpdatedAt=0, batteryUpdatedAt=0, rangeUpdatedAt=0, vehicleStatus = "연결 대기", navigationStatus = "연결 대기") }
    }
}
