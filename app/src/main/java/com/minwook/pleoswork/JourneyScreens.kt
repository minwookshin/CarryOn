package com.minwook.pleoswork

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun energyLabel(e: JourneyEstimate): String = listOfNotNull(e.fuelPercent?.let { "연료 $it%" },e.batteryPercent?.let { "배터리 $it%" },e.rangeKm?.let { "%.0f km".format(it) }).joinToString(" · ").ifEmpty { "에너지 정보 미수신" }
@Composable private fun ScrollBody(content: @Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content) }
@Composable fun FlowSteps(s: WorkState) {
    val stage=when {s.ended->4;s.phase=="prepare"->0;s.phase=="transit"->1;s.phase in setOf("arrival","field")->2;else->3}
    val labels=listOf("출발 준비","이동 중","현장 작업","업무 검토","운송 완료")
    Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.control).padding(horizontal=WorkDimens.inset,vertical=12.dp).clearAndSetSemantics {
        contentDescription="전체 5단계 중 ${stage+1}단계, ${labels[stage]}, 정차 ${s.stopIndex+1}/${s.routeStops.size}"
        liveRegion=LiveRegionMode.Polite
    },horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(labels[stage],style=MaterialTheme.typography.titleLarge)
        Text("${s.stopIndex+1} / ${s.routeStops.size} 정차",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Divider()
}
@Composable fun JourneySchedule(s: WorkState,arrivals: List<Int?>,viewedStop: Int?=null,select: (Int)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        s.routeStops.forEachIndexed {i,stop ->
            val current=i==s.stopIndex
            val completed=stop.id in s.completedStops
            val showing=i==(viewedStop ?: s.stopIndex)
            Row(Modifier.fillMaxWidth().background(if(showing) MaterialTheme.colorScheme.primary.copy(alpha=.08f) else Color.Transparent)
                .heightIn(min=WorkDimens.listRow).carFocusOutline("stop/$i").semantics { selected=showing; stateDescription=if(completed) "완료" else if(current) "현재 정차" else "예정" }
                .clickable(role=Role.Button,onClickLabel="정차 일정 보기") {select(i)}.padding(horizontal=20.dp,vertical=24.dp),
                horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text("%02d".format(i+1),Modifier.padding(top=2.dp),style=MaterialTheme.typography.titleMedium,
                    color=if(showing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stop.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        CarbonIcon(R.drawable.ic_arrow_right,null)
                    }
                    Text(stop.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("예정 ${stop.time}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(completed) "완료" else arrivals.getOrNull(i)?.let {"${if(stop.id in s.arrivedAt) "도착" else "도착 예상"} ${clockLabel(it)}${if(current) " · 현재" else ""}"} ?: "도착 예상 미수신",
                        style=MaterialTheme.typography.bodyMedium,fontWeight=if(current) FontWeight.Medium else FontWeight.Normal)
                }
            }
            Divider()
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun StopDetails(s: WorkState,index: Int,arrivals: List<Int?>) {
    val stop=s.routeStops[index]
    Column(Modifier.widthIn(max=720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stop.name,Modifier.semantics { heading() },style=MaterialTheme.typography.headlineLarge)
            Text(stop.detail,style=MaterialTheme.typography.titleLarge)
            Text("${index+1} / ${s.routeStops.size} 정차 · ${if(stop.id in s.completedStops) "완료" else if(index==s.stopIndex) "현재 정차" else "예정"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            StopFact("도착 예정",stop.time,Modifier.widthIn(min=152.dp))
            StopFact(if(stop.id in s.arrivedAt) "도착 기록" else if(s.demoMode) "도착 예상 · 데모" else "도착 예상 · 내비",arrivals.getOrNull(index)?.let(::clockLabel) ?: "미수신",Modifier.widthIn(min=152.dp))
        }
        Divider()
        StopFact("진입 위치",stop.entrance)
        StopFact("현장 예상","${stop.serviceMinutes}분")
    }
}
@Composable private fun StopFact(label: String,value: String,modifier: Modifier=Modifier) {
    Column(modifier.semantics(mergeDescendants=true) {},verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun JourneyNumbers(e: JourneyEstimate,eta: Int?,energy: ()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        TripValue(eta?.let(::clockLabel) ?: "—","도착 예상 · ${e.source}",Modifier.weight(1f))
        TripValue(e.seconds?.let { kotlin.math.ceil(it/60.0).toInt().toString() } ?: "—","이동 시간",Modifier.weight(1f),"분")
        TripValue(e.km?.let {"%.1f".format(it)} ?: "—","남은 거리",Modifier.weight(1f),"km")
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min=WorkDimens.control).carFocusOutline("energy")
        .clickable(role=Role.Button,onClickLabel="연료와 배터리 상세 보기",onClick=energy).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(energyLabel(e),style=MaterialTheme.typography.titleMedium)
            Text(if(e.lowRange) "도착 후 여유 부족 · 주유·충전 확인" else e.arrivalRange?.let {"도착 후 예상 여유 %.0f km".format(it.coerceAtLeast(0.0))} ?: "도착 후 여유 계산 대기",
                color=if(e.lowRange) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        CarbonIcon(R.drawable.ic_arrow_right,null)
    }
}
@Composable fun PreparePanel(s: WorkState,e: JourneyEstimate,eta: Int?,allowed: Boolean,repo: WorkRepository,navigation: ()->Unit,energy: ()->Unit) {
    TaskLayout(
        hint=if(s.restStartedAt!=null) "휴식을 종료하면 출발할 수 있어요." else "출발 전 물품·운송장, 적재 고정, 운송 일정을 확인해 주세요.",
        actions={
            ActionButton("경로 보기",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_location,onClick=navigation)
            ActionButton(if(s.completedStops.isEmpty()) "운송 시작" else "다음 정차로 출발",Modifier.weight(2f),enabled=allowed && e.seconds!=null && s.restStartedAt==null) {
                repo.update {JourneyReducer.depart(it,allowed,e.seconds!=null)}
            }
        },
    ) {
        StopHeading(s)
        if(s.scenario=="parcel") Text("배송 ${s.stop.quantity}개 · 회수 ${s.stop.pickups}건",style=MaterialTheme.typography.titleMedium)
        JourneyNumbers(e,eta,energy)
        if(!s.demoMode && e.seconds==null) Text("경로 보기에서 현재 내비 목적지를 이 정차지에 연결하세요.",color=MaterialTheme.colorScheme.error)
    }
}
@Composable fun TransitPanel(s: WorkState,e: JourneyEstimate,eta: Int?,allowed: Boolean,repo: WorkRepository,localSeconds: Int,navigation: ()->Unit,energy: ()->Unit) {
    TaskLayout(actions={
        if(s.demoMode) {
            ActionButton("1분 진행",Modifier.weight(1f),secondary=true,enabled=allowed,icon=R.drawable.ic_play) {repo.update {JourneyReducer.advance(it,60,allowed)}}
            ActionButton("도착으로 이동",Modifier.weight(2f),enabled=allowed,icon=R.drawable.ic_location) {repo.update {JourneyReducer.advance(it,Int.MAX_VALUE,allowed)}}
        } else {
            ActionButton("경로 보기",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_location,onClick=navigation)
            ActionButton("도착·주차 확인",Modifier.weight(2f),enabled=allowed,icon=R.drawable.ic_check) {repo.update {JourneyReducer.arrive(it,allowed,localSeconds)}}
        }
    }) {
        StopHeading(s)
        if(!s.demoMode) s.bindings[s.stop.id]?.let { Text("내비 목적지 · ${it.name}",style=MaterialTheme.typography.bodySmall) }
        JourneyNumbers(e,eta,energy)
        if(s.demoMode) {
            LinearProgressIndicator(progress={(s.travelElapsed.toFloat()/(s.stop.driveSeconds+s.trafficDelay)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),trackColor=MaterialTheme.colorScheme.outlineVariant)
            QuietButton(onClick={repo.update {JourneyReducer.delay(it,allowed)}},enabled=allowed,focusKey="delay") {Text("정체 상황 +5분 · 데모")}
        }
    }
}
@Composable fun RoutePanel(s: WorkState,car: VehicleState,gateway: PleosGateway,allowed: Boolean,now: Long,bind: (NavTarget)->Unit) {
    val reading=car.navigation?.takeIf {it.fresh(now)}
    ScrollBody {
        if(s.demoMode) {
            Text(s.stop.name,style=MaterialTheme.typography.headlineLarge)
            DataRow("이동 예상",durationLabel(if(s.phase in setOf("arrival","field","return","finish")) 0 else (s.stop.driveSeconds-s.travelElapsed).coerceAtLeast(0)))
            DataRow("구간 거리","%.1f km".format(s.stop.driveKm));DataRow("현장 예상","${s.stop.serviceMinutes}분")
            Text("데모 경로입니다. 시간 진행과 교통 정체를 바꾸면 도착 예상과 이후 일정이 함께 갱신됩니다.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(reading?.target?.name ?: "목적지 수신 대기",style=MaterialTheme.typography.headlineLarge)
            reading?.let {
                Text(it.target.address);DataRow("남은 시간",durationLabel(it.seconds));DataRow("남은 거리","%.1f km".format(it.meters/1000.0))
                ActionButton("${s.stop.name} 일정에 연결",Modifier.fillMaxWidth(),enabled=allowed && s.phase=="prepare",icon=R.drawable.ic_check) {bind(it.target)}
            }
            Text("Pleos 내비의 현재 목적지와 도착 정보를 받습니다. 다른 목적지로 바뀌면 기존 일정에는 적용하지 않습니다.",style=MaterialTheme.typography.bodySmall)
            if(car.routeMessage.isNotBlank()) Text(car.routeMessage)
            ActionButton("내비 정보 다시 읽기",Modifier.fillMaxWidth(),secondary=true,icon=R.drawable.ic_refresh) {gateway.refreshNavigation()}
            ActionButton("경로 취소",Modifier.fillMaxWidth(),secondary=true,enabled=allowed && reading!=null,icon=R.drawable.ic_close) {gateway.cancelRoute()}
        }
    }
}
@Composable fun EnergyPanel(s: WorkState,e: JourneyEstimate,allowed: Boolean,repo: WorkRepository,gateway: PleosGateway) {
    ScrollBody {
        Text("현재 에너지",style=MaterialTheme.typography.headlineLarge)
        DataRow("데이터",if(s.demoMode) "데모" else "차량 API")
        DataRow("연료",e.fuelPercent?.let {"$it%"} ?: "미지원·미수신");DataRow("배터리",e.batteryPercent?.let {"$it%"} ?: "미지원·미수신")
        DataRow("주행 가능 거리",e.rangeKm?.let {"%.1f km".format(it)} ?: "미지원·미수신")
        DataRow("도착 후 예상 여유",e.arrivalRange?.let {"%.1f km".format(it)} ?: "계산 대기")
        Text("예상 여유 = 주행 가능 거리 − 남은 경로 거리. 30 km 미만이면 주유·충전 확인을 표시합니다. 실제 주행 조건에 따라 달라질 수 있어요.",style=MaterialTheme.typography.bodySmall)
        if(s.demoMode) ActionButton(if(s.demoEnergyType=="fuel") "주유 완료 · 데모" else "충전 완료 · 데모",Modifier.fillMaxWidth(),enabled=allowed,icon=R.drawable.ic_check) {repo.update {it.copy(demoFuelPercent=90.0,demoRangeKm=405.0,demoClock=it.demoClock+600,events=(listOf("에너지 보충 · 데모 10분")+it.events).take(100))}}
        else ActionButton("차량에서 다시 읽기",Modifier.fillMaxWidth(),secondary=true,icon=R.drawable.ic_refresh) {gateway.refreshEnergy()}
    }
}
@Composable fun DemoPanel(s: WorkState,allowed: Boolean,repo: WorkRepository,close: ()->Unit) {
    ScrollBody {
        Text("이동과 작업을 직접 진행하세요",style=MaterialTheme.typography.headlineMedium)
        Text("여기서 바꾸는 수치는 데모에만 적용됩니다.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton("연료 차량",Modifier.weight(1f),secondary=s.demoEnergyType!="fuel",enabled=allowed,icon=R.drawable.ic_truck,isSelected=s.demoEnergyType=="fuel") {repo.update {it.copy(demoEnergyType="fuel")}}
            ActionButton("전기차",Modifier.weight(1f),secondary=s.demoEnergyType!="ev",enabled=allowed,icon=R.drawable.ic_truck,isSelected=s.demoEnergyType=="ev") {repo.update {it.copy(demoEnergyType="ev")}}
        }
        ActionButton("잔량 부족 상황",Modifier.fillMaxWidth(),secondary=true,enabled=allowed) {repo.update {it.copy(demoFuelPercent=5.0,demoRangeKm=18.0)};close()}
        ActionButton("정체 +5분",Modifier.fillMaxWidth(),secondary=true,enabled=allowed && s.phase in setOf("prepare","transit"),icon=R.drawable.ic_time) {repo.update {JourneyReducer.delay(it,allowed)};close()}
        ActionButton("현재 정차의 남은 예시 물품 완료",Modifier.fillMaxWidth(),secondary=true,enabled=allowed && s.scenario=="parcel" && s.phase in setOf("arrival","field","return"),icon=R.drawable.ic_check) {repo.update {JourneyReducer.completeDemoStop(it,allowed)};close()}
    }
}
@Composable fun JourneySettings(s: WorkState,parked: Boolean,select: (String)->Unit,connection: ()->Unit,mode: (Boolean)->Unit,reset: ()->Unit) {
    var pending by remember {mutableStateOf("")}
    val focus=LocalWorkFocus.current
    val scope=LocalFocusScope.current
    var resetOrigin by remember { mutableStateOf<String?>(null) }
    fun confirm(kind: String) { resetOrigin=focus?.current; pending=kind; focus?.moveTo("$scope/취소") }
    ScrollBody {
        Text("데이터",style=MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton("데모",Modifier.weight(1f),secondary=!s.demoMode,enabled=parked && s.phase=="prepare" && s.completedStops.isEmpty(),icon=R.drawable.ic_play,isSelected=s.demoMode) {mode(true)}
            ActionButton("실제 API",Modifier.weight(1f),secondary=s.demoMode,enabled=parked && s.phase=="prepare" && s.completedStops.isEmpty(),icon=R.drawable.ic_info,isSelected=!s.demoMode) {mode(false)}
        }
        Text("데이터 모드는 첫 출발 전에 바꿀 수 있어요.",style=MaterialTheme.typography.bodySmall)
        ActionButton("차량·내비 연결 상태",Modifier.fillMaxWidth(),secondary=true,icon=R.drawable.ic_info,onClick=connection)
        Divider();Text("새 데모 시작",style=MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton("택배",Modifier.weight(1f),secondary=true,enabled=parked,icon=R.drawable.ic_delivery) {confirm("parcel")}
            ActionButton("화물",Modifier.weight(1f),secondary=true,enabled=parked,icon=R.drawable.ic_truck) {confirm("freight")}
        }
        ActionButton("현재 업무 초기화",Modifier.fillMaxWidth(),secondary=true,enabled=parked,icon=R.drawable.ic_reset) {confirm("reset")}
        if(pending.isNotEmpty()) {
            Text("현재 예시 작업 기록을 지우고 다시 시작할까요?",Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ActionButton("취소",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_close) {pending="";resetOrigin?.let {focus?.moveTo(it)}}
                ActionButton("다시 시작",Modifier.weight(1f),enabled=parked,icon=R.drawable.ic_reset) {if(pending=="reset") reset() else select(pending)}
            }
        }
    }
}
@Composable fun EndSummary(s: WorkState,settings: ()->Unit) {
    TaskLayout(actions={ActionButton("새 데모 시작",Modifier.fillMaxWidth(),secondary=true,icon=R.drawable.ic_reset,onClick=settings)}) {
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            CarbonIcon(R.drawable.ic_check,null)
            Text("오늘의 운송을 마쳤어요",Modifier.semantics { heading() },style=MaterialTheme.typography.headlineLarge)
        }
        if(s.scenario=="parcel") {
            Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                TripValue(s.deliveryCount.toString(),"배송 확인",Modifier.weight(1f),"개")
                TripValue(s.pickupCount.toString(),"회수 확인",Modifier.weight(1f),"건")
            }
            DataRow("보류","${s.deferredCount}건")
        } else DataRow("화물 인계","12팔레트 · 인수 확인 완료")
        DataRow("완료한 정차","${s.completedStops.size} / ${s.routeStops.size}")
        if(s.demoMode) DataRow("운송 거리 · 데모","%.1f km".format(s.travelledKm))
        DataRow("휴식",durationLabel((s.restMs/1000).toInt()))
    }
}
