package com.example.stephealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStepStore(this)
        val repository = HealthConnectRepository(this)

        setContent {
            MaterialTheme {
                StepDashboardScreen(store = store, repository = repository)
            }
        }
    }
}

@Composable
fun StepDashboardScreen(store: LocalStepStore, repository: HealthConnectRepository) {
    var records by remember { mutableStateOf(store.loadDailySteps()) }
    var syncState by remember { mutableStateOf(store.loadSyncState()) }
    var message by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(syncState.permissionGranted) }
    var isSyncing by remember { mutableStateOf(false) }
    var providerStatus by remember { mutableStateOf(repository.sdkStatus()) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
        onResult = { granted ->
            permissionGranted = granted.containsAll(repository.permissions)
            syncState = syncState.copy(permissionGranted = permissionGranted)
            store.saveSyncState(syncState)
            message = if (permissionGranted) "権限を許可しました" else "権限が未許可です"
            if (permissionGranted) {
                scope.launch { sync(repository, store) { newRecords, newState, newMessage, syncing ->
                    records = newRecords
                    syncState = newState
                    message = newMessage
                    isSyncing = syncing
                } }
            }
        }
    )

    LaunchedEffect(Unit) {
        providerStatus = repository.sdkStatus()
        permissionGranted = repository.hasAllPermissions()
        syncState = store.loadSyncState().copy(
            permissionGranted = permissionGranted,
            healthSourceAvailable = providerStatus == HealthConnectClient.SDK_AVAILABLE,
        )
        store.saveSyncState(syncState)
        if (permissionGranted && providerStatus == HealthConnectClient.SDK_AVAILABLE) {
            sync(repository, store) { newRecords, newState, newMessage, syncing ->
                records = newRecords
                syncState = newState
                message = newMessage
                isSyncing = syncing
            }
        } else if (providerStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            message = "Health Connect のインストールまたは更新が必要です"
        } else if (providerStatus != HealthConnectClient.SDK_AVAILABLE) {
            message = "この端末では Health Connect が利用できません"
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("累計歩数") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("今日の歩数", style = MaterialTheme.typography.titleSmall)
                        Text(todaySteps(records).toString(), style = MaterialTheme.typography.displaySmall)
                        SummaryRow("総累計", records.sumOf { it.steps })
                        SummaryRow("直近7日", lastNDaysTotal(records, 7))
                        SummaryRow("今月", thisMonthTotal(records))
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SummaryRow("権限", if (permissionGranted) 1 else 0, true)
                        SummaryRow("データソース", if (providerStatus == HealthConnectClient.SDK_AVAILABLE) 1 else 0, true, if (providerStatus == HealthConnectClient.SDK_AVAILABLE) "Health Connect" else "未接続")
                        SummaryRow("最終同期", 0, true, formatEpoch(syncState.lastSuccessAtEpochMillis))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { permissionLauncher.launch(repository.permissions) },
                                enabled = providerStatus == HealthConnectClient.SDK_AVAILABLE
                            ) { Text("権限を許可") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        sync(repository, store) { newRecords, newState, newMessage, syncing ->
                                            records = newRecords
                                            syncState = newState
                                            message = newMessage
                                            isSyncing = syncing
                                        }
                                    }
                                },
                                enabled = permissionGranted && !isSyncing && providerStatus == HealthConnectClient.SDK_AVAILABLE
                            ) { Text(if (isSyncing) "同期中..." else "今すぐ同期") }
                        }
                        if (message.isNotBlank()) {
                            Text(message)
                        }
                    }
                }
            }
            items(records) { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(record.date)
                            Text(record.source, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${record.steps} 歩")
                    }
                }
            }
        }
    }
}

private suspend fun sync(
    repository: HealthConnectRepository,
    store: LocalStepStore,
    apply: (List<DailyStepRecord>, SyncState, String, Boolean) -> Unit,
) {
    apply(store.loadDailySteps(), store.loadSyncState(), "", true)
    try {
        val fresh = repository.readDailySteps(
            startDate = LocalDate.now().minusDays(89),
            endDate = LocalDate.now(),
        )
        store.replaceDailySteps(fresh)
        val newState = SyncState(
            lastSyncAtEpochMillis = Instant.now().toEpochMilli(),
            lastSuccessAtEpochMillis = Instant.now().toEpochMilli(),
            permissionGranted = true,
            healthSourceAvailable = true,
        )
        store.saveSyncState(newState)
        apply(store.loadDailySteps(), newState, "日次歩数を再取得して保存しました", false)
    } catch (e: Exception) {
        val fallbackState = store.loadSyncState().copy(lastSyncAtEpochMillis = Instant.now().toEpochMilli())
        store.saveSyncState(fallbackState)
        apply(store.loadDailySteps(), fallbackState, e.message ?: "同期に失敗しました", false)
    }
}

@Composable
private fun SummaryRow(title: String, value: Long, textMode: Boolean = false, overrideText: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Text(overrideText ?: if (textMode) if (value == 1L) "許可済み" else "未許可" else value.toString())
    }
}

private fun todaySteps(records: List<DailyStepRecord>): Long {
    val today = LocalDate.now().toString()
    return records.firstOrNull { it.date == today }?.steps ?: 0L
}

private fun lastNDaysTotal(records: List<DailyStepRecord>, days: Int): Long {
    val targets = (0 until days).map { LocalDate.now().minusDays(it.toLong()).toString() }.toSet()
    return records.filter { it.date in targets }.sumOf { it.steps }
}

private fun thisMonthTotal(records: List<DailyStepRecord>): Long {
    val prefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
    return records.filter { it.date.startsWith(prefix) }.sumOf { it.steps }
}

private fun formatEpoch(epochMillis: Long?): String {
    if (epochMillis == null) return "未同期"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
