package com.minwook.pleoswork

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

private val Parcel.statusLabel: String get() = when(status) {
    "done" -> "확인됨"
    "deferred" -> "보류"
    else -> "대기"
}

/** The list stays mounted while only its neighboring detail changes. */
@Composable fun ParcelWorkspace(
    s: WorkState, inventory: Boolean, selectedId: String?, allowed: Boolean,
    scroll: ScrollState, select: (String) -> Unit, closeDetail: () -> Unit,
    exit: () -> Unit, demoComplete: () -> Unit,
    detail: @Composable (Parcel) -> Unit,
) {
    val items=if(inventory) s.parcels else s.fieldItems
    val item=items.find {it.id==selectedId}
    Row(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFocusScope provides if(inventory) "inventory-list" else "field-list") {
            Column(Modifier.weight(.34f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)
                .semantics { isTraversalGroup=true; traversalIndex=0f }) {
                SectionHeader(if(inventory) "물품 목록" else "현장 작업",exit,if(inventory) "업무 화면으로" else "재탑승")
                Divider()
                Box(Modifier.weight(1f)) { ParcelList(items,true,selectedId,scroll,select) }
                Divider()
                Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.control).padding(horizontal=WorkDimens.inset),
                    verticalAlignment=Alignment.CenterVertically) {
                    Text("${items.count {it.status=="done"}} / ${items.size} 확인 · ${items.count {it.status=="deferred"}} 보류",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        CompositionLocalProvider(LocalFocusScope provides if(item!=null) "panel/item" else "main") {
            Column(Modifier.weight(.66f).fillMaxHeight().semantics { isTraversalGroup=true; traversalIndex=1f }) {
                if(item!=null) Panel(if(inventory) "물품 상세" else "현장 확인",closeDetail,"물품 목록으로") {
                    detail(item)
                } else {
                    SectionHeader(if(inventory) "물품 요약" else s.stop.name)
                    Divider()
                    Box(Modifier.weight(1f).workFocus("entry").focusGroup()) {
                        TaskLayout(actions=if(!inventory) ({
                            if(s.demoMode) QuietButton(onClick=demoComplete,enabled=allowed,focusKey="demo-complete",modifier=Modifier.weight(1f)) {
                                Text("남은 물품 완료 · 데모")
                            }
                            ActionButton("재탑승",Modifier.weight(1f),secondary=true,enabled=allowed,onClick=exit)
                        }) else null) {
                            Row(horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                                TripValue(items.count {!it.pickup}.toString(),"배송 물품",unit="개")
                                TripValue(items.count {it.pickup}.toString(),"회수 물품",unit="건")
                            }
                            Text("동·호수를 선택하면 물품 정보를 볼 수 있어요.",style=MaterialTheme.typography.bodyMedium,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ParcelList(
    items: List<Parcel>, allowed: Boolean, selectedId: String?=null,
    scroll: ScrollState=rememberScrollState(), select: (String) -> Unit,
) {
    val grouped=items.groupBy {it.stop}
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        grouped.forEach { (stopId,parcels) ->
            if(grouped.size>1) Text(Stops.find {it.id==stopId}?.name ?: stopId,
                Modifier.fillMaxWidth().padding(horizontal=WorkDimens.inset,vertical=16.dp).semantics { heading() },
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            parcels.forEach { item -> key(item.id) {
                val selected=item.id==selectedId
                val reveal=remember {BringIntoViewRequester()}
                LaunchedEffect(selected) { if(selected) reveal.bringIntoView() }
                Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.listRow)
                    .background(if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.08f) else Color.Transparent)
                    .bringIntoViewRequester(reveal).carFocusOutline("item/${item.id}")
                    .semantics { stateDescription=item.statusLabel }
                    .selectable(selected,enabled=allowed,role=Role.Button,onClick={select(item.id)})
                    .padding(horizontal=WorkDimens.inset,vertical=12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(WorkDimens.gap)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("${item.building} ${item.unit}",style=MaterialTheme.typography.titleMedium)
                        Text("${item.reference} · ${if(item.pickup) "송장 확인" else item.zone+" 구역"}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(item.statusLabel,style=MaterialTheme.typography.bodySmall,
                        color=if(item.status=="done" || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    CarbonIcon(R.drawable.ic_arrow_right,null)
                }
                Divider()
            } }
        }
    }
}

@Composable fun FieldPanel(s: WorkState, allowed: Boolean, scroll: ScrollState, select: (String) -> Unit, back: () -> Unit, demoComplete: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(s.stop.name)
        Divider()
        CompositionLocalProvider(LocalFocusScope provides "field-list") {
            Box(Modifier.weight(1f)) { ParcelList(s.fieldItems,true,scroll=scroll,select=select) }
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(WorkDimens.inset),horizontalArrangement=Arrangement.spacedBy(WorkDimens.gap)) {
            if(s.demoMode) QuietButton(onClick=demoComplete,enabled=allowed,focusKey="demo-complete",modifier=Modifier.weight(1f)) {Text("남은 물품 완료 · 데모")}
            ActionButton("재탑승",Modifier.weight(1f),secondary=true,enabled=allowed,onClick=back)
        }
    }
}

@Composable fun ItemDetail(item: Parcel, allowed: Boolean, record: (String, Boolean, String) -> Unit) {
    val focus=LocalWorkFocus.current
    val scope=LocalFocusScope.current
    var proof by rememberSaveable(item.id,item.status) { mutableStateOf(false) }
    var deferring by rememberSaveable(item.id,item.status) { mutableStateOf(false) }
    var reason by rememberSaveable(item.id,item.status) { mutableStateOf("") }
    LaunchedEffect(item.id,item.status,allowed,deferring) {
        focus?.moveTo(if(!allowed) "$scope/back" else if(deferring) "$scope/reasons" else "$scope/item-controls")
    }
    Column(Modifier.fillMaxSize().workFocus("item-controls").focusGroup()) {
        TaskLayout(actions=if(allowed) ({
            when {
                item.status!="pending" -> ActionButton("처리 취소",Modifier.fillMaxWidth(),secondary=true,icon=R.drawable.ic_undo) {record("pending",false,"")}
                deferring -> {
                    ActionButton("취소",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_close) {deferring=false}
                    ActionButton("보류 기록",Modifier.weight(2f),enabled=reason.isNotBlank(),icon=R.drawable.ic_save) {record("deferred",false,reason)}
                }
                else -> {
                    ActionButton("보류",Modifier.weight(1f),secondary=true,icon=R.drawable.ic_pause) {deferring=true}
                    ActionButton(if(item.pickup) "회수 확인" else "배송 확인",Modifier.weight(2f),enabled=proof,icon=R.drawable.ic_check) {record("done",true,"")}
                }
            }
        }) else null) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${item.building} ${item.unit}",Modifier.semantics {heading();liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.headlineLarge)
                Text("${Stops.find {it.id==item.stop}?.name.orEmpty()} · ${item.reference} · ${if(item.pickup) "회수" else item.zone+" 구역"}",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.statusLabel,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
            }
            Text(item.note,style=MaterialTheme.typography.titleLarge)
            if(item.status=="deferred") Text("보류 사유 · ${item.reason}",style=MaterialTheme.typography.bodyMedium)
            if(!allowed) Text("현재 정차의 현장 작업에서 처리할 수 있어요. 휴식 중이면 먼저 종료하세요.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(item.status=="pending") {
                Divider()
                if(deferring) {
                    Text("보류 사유",Modifier.semantics {heading()},style=MaterialTheme.typography.titleMedium)
                    Column(Modifier.workFocus("reasons").focusGroup().selectableGroup()) {
                        listOf("수취인 부재","출입 불가","주소 확인 필요","물품 확인 필요").forEach { value ->
                            Row(Modifier.fillMaxWidth().heightIn(min=WorkDimens.control).carFocusOutline(value)
                                .selectable(reason==value,role=Role.RadioButton,onClick={reason=value}).padding(horizontal=12.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(reason==value,onClick=null)
                                Text(value,Modifier.padding(start=12.dp))
                            }
                        }
                    }
                } else CheckRow(if(item.pickup) "반품 물품·송장을 확인했어요" else "물품 전달·배송 위치를 확인했어요",proof,focusKey="proof") {proof=it}
            }
        }
    }
}
