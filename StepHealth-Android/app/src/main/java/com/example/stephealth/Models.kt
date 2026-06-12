package com.example.stephealth

import java.time.Instant

data class DailyStepRecord(
    val date: String,
    val steps: Long,
    val source: String,
    val syncedAtEpochMillis: Long = Instant.now().toEpochMilli(),
)

data class SyncState(
    val lastSyncAtEpochMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val permissionGranted: Boolean = false,
    val healthSourceAvailable: Boolean = false,
)
