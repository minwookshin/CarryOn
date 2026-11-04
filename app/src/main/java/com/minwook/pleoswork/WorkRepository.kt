package com.minwook.pleoswork

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WorkRepository(context: Context) {
    private val prefs = context.getSharedPreferences("delivery-work-v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): WorkState = runCatching {
        val json = prefs.getString("state", null) ?: return WorkState()
        val previous = com.google.gson.JsonParser.parseString(json).asJsonObject
        require(previous.get("version")?.asInt in listOf(1, 2))
        val merged = gson.toJsonTree(WorkState()).asJsonObject
        previous.entrySet().forEach { (key, value) -> if (!value.isJsonNull) merged.add(key, value) }
        if (previous.get("version")?.asInt == 1) {
            merged.addProperty("version", 2)
            // Preserve parcel history while adding the missing departure stage.
            if (previous.get("phase")?.asString == "arrival") merged.addProperty("phase", "prepare")
        }
        val saved = gson.fromJson(merged, WorkState::class.java)
        require(saved.version == 2 && saved.stopIndex in saved.routeStops.indices && saved.parcels.size == 28)
        require(saved.phase in setOf("prepare","transit","arrival","field","return","finish"))
        require(saved.parcels.map { it.id }.toSet() == seedParcels().map { it.id }.toSet())
        require(saved.parcels.all { it.status in setOf("pending", "done", "deferred") && (it.status != "done" || it.proof) && (it.status != "deferred" || it.reason.isNotBlank()) })
        saved
    }.getOrElse { WorkState() }

    @Synchronized fun update(transform: (WorkState) -> WorkState) {
        val next = transform(mutable.value)
        if (next == mutable.value) return
        prefs.edit().putString("state", gson.toJson(next)).apply()
        mutable.value = next
    }
}
