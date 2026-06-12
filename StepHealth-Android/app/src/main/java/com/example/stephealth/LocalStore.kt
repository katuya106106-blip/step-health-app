package com.example.stephealth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LocalStepStore(context: Context) {
    private val prefs = context.getSharedPreferences("step_health_store", Context.MODE_PRIVATE)
    private val dailyKey = "daily_steps"
    private val syncKey = "sync_state"

    fun loadDailySteps(): List<DailyStepRecord> {
        val raw = prefs.getString(dailyKey, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    DailyStepRecord(
                        date = obj.getString("date"),
                        steps = obj.getLong("steps"),
                        source = obj.getString("source"),
                        syncedAtEpochMillis = obj.getLong("syncedAtEpochMillis"),
                    )
                )
            }
        }.sortedByDescending { it.date }
    }

    fun replaceDailySteps(records: List<DailyStepRecord>) {
        val array = JSONArray()
        records.sortedByDescending { it.date }.forEach { record ->
            array.put(
                JSONObject()
                    .put("date", record.date)
                    .put("steps", record.steps)
                    .put("source", record.source)
                    .put("syncedAtEpochMillis", record.syncedAtEpochMillis)
            )
        }
        prefs.edit().putString(dailyKey, array.toString()).apply()
    }

    fun loadSyncState(): SyncState {
        val raw = prefs.getString(syncKey, null) ?: return SyncState()
        val obj = JSONObject(raw)
        return SyncState(
            lastSyncAtEpochMillis = obj.optLong("lastSyncAtEpochMillis").takeIf { it != 0L },
            lastSuccessAtEpochMillis = obj.optLong("lastSuccessAtEpochMillis").takeIf { it != 0L },
            permissionGranted = obj.optBoolean("permissionGranted", false),
            healthSourceAvailable = obj.optBoolean("healthSourceAvailable", false),
        )
    }

    fun saveSyncState(state: SyncState) {
        val obj = JSONObject()
            .put("lastSyncAtEpochMillis", state.lastSyncAtEpochMillis ?: 0L)
            .put("lastSuccessAtEpochMillis", state.lastSuccessAtEpochMillis ?: 0L)
            .put("permissionGranted", state.permissionGranted)
            .put("healthSourceAvailable", state.healthSourceAvailable)
        prefs.edit().putString(syncKey, obj.toString()).apply()
    }
}
