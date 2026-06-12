package com.example.stephealth

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectRepository(private val context: Context) {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun sdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE_NAME)
    }

    fun isAvailable(): Boolean {
        return sdkStatus() == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return client.permissionController.getGrantedPermissions().containsAll(permissions)
    }

    suspend fun readDailySteps(startDate: LocalDate, endDate: LocalDate): List<DailyStepRecord> {
        require(!endDate.isBefore(startDate)) { "endDate must be on or after startDate" }
        check(isAvailable()) { "Health Connect is not available on this device." }

        val zone = ZoneId.systemDefault()
        val syncedAt = Instant.now().toEpochMilli()
        val output = mutableListOf<DailyStepRecord>()
        var cursor = startDate

        while (!cursor.isAfter(endDate)) {
            val startInstant = cursor.atStartOfDay(zone).toInstant()
            val endInstant = cursor.plusDays(1).atStartOfDay(zone).toInstant()
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startInstant, endInstant),
                )
            )
            output += DailyStepRecord(
                date = cursor.toString(),
                steps = response[StepsRecord.COUNT_TOTAL] ?: 0L,
                source = "android_health_connect",
                syncedAtEpochMillis = syncedAt,
            )
            cursor = cursor.plusDays(1)
        }

        return output.sortedByDescending { it.date }
    }

    companion object {
        const val PROVIDER_PACKAGE_NAME = "com.google.android.apps.healthdata"
    }
}
