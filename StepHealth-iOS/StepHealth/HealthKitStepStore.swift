import Foundation
import HealthKit
import CoreMotion

final class LocalStepStore {
    private let recordsURL: URL
    private let syncURL: URL

    init(recordsFilename: String = "daily_steps.json", syncFilename: String = "sync_state.json") {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.recordsURL = base.appendingPathComponent(recordsFilename)
        self.syncURL = base.appendingPathComponent(syncFilename)
    }

    func loadRecords() -> [DailyStepRecord] {
        guard let data = try? Data(contentsOf: recordsURL) else { return [] }
        return (try? JSONDecoder().decode([DailyStepRecord].self, from: data)) ?? []
    }

    func replaceAllRecords(_ records: [DailyStepRecord]) throws {
        let data = try JSONEncoder().encode(records.sorted { $0.date > $1.date })
        try data.write(to: recordsURL, options: .atomic)
    }

    func loadSyncState() -> SyncState {
        guard let data = try? Data(contentsOf: syncURL) else {
            return SyncState(lastSyncAt: nil, lastSuccessAt: nil, permissionStatus: "unknown", healthSourceAvailable: false)
        }
        return (try? JSONDecoder().decode(SyncState.self, from: data)) ?? SyncState(lastSyncAt: nil, lastSuccessAt: nil, permissionStatus: "unknown", healthSourceAvailable: false)
    }

    func saveSyncState(_ state: SyncState) throws {
        let data = try JSONEncoder().encode(state)
        try data.write(to: syncURL, options: .atomic)
    }
}

final class HealthKitStepService {
    private let healthStore = HKHealthStore()
    private let stepType = HKQuantityType.quantityType(forIdentifier: .stepCount)!

    var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    func requestAuthorization() async throws {
        try await withCheckedThrowingContinuation { continuation in
            healthStore.requestAuthorization(toShare: [], read: [stepType]) { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume(returning: ())
                } else {
                    continuation.resume(throwing: NSError(domain: "HealthKit", code: 1, userInfo: [NSLocalizedDescriptionKey: "HealthKit permission was not granted."]))
                }
            }
        }
    }

    func fetchDailyTotals(startDate: Date, endDate: Date) async throws -> [DailyStepRecord] {
        let calendar = Calendar.current
        let interval = DateComponents(day: 1)
        let anchorDate = calendar.startOfDay(for: startDate)

        return try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: startDate, end: endDate, options: .strictStartDate)
            let query = HKStatisticsCollectionQuery(
                quantityType: stepType,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum,
                anchorDate: anchorDate,
                intervalComponents: interval
            )

            query.initialResultsHandler = { _, results, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let results else {
                    continuation.resume(returning: [])
                    return
                }

                var output: [DailyStepRecord] = []
                results.enumerateStatistics(from: startDate, to: endDate) { statistics, _ in
                    let count = Int(statistics.sumQuantity()?.doubleValue(for: HKUnit.count()) ?? 0)
                    let date = ISO8601DateFormatter.dayFormatter.string(from: statistics.startDate)
                    output.append(DailyStepRecord(date: date, steps: count, source: "ios_healthkit", syncedAt: Date()))
                }
                continuation.resume(returning: output.sorted { $0.date > $1.date })
            }
            healthStore.execute(query)
        }
    }

    func isPedometerLiveUpdatesAvailable() -> Bool {
        CMPedometer.isStepCountingAvailable()
    }
}

@MainActor
final class StepDashboardViewModel: ObservableObject {
    @Published var records: [DailyStepRecord] = []
    @Published var isAuthorized = false
    @Published var isSyncing = false
    @Published var errorMessage: String?
    @Published var lastSyncAt: Date?

    private let store = LocalStepStore()
    private let service = HealthKitStepService()

    var totalSteps: Int { records.reduce(0) { $0 + $1.steps } }
    var todaySteps: Int {
        let today = ISO8601DateFormatter.dayFormatter.string(from: Date())
        return records.first(where: { $0.date == today })?.steps ?? 0
    }
    var weeklySteps: Int {
        let dates = lastNDates(7)
        return records.filter { dates.contains($0.date) }.reduce(0) { $0 + $1.steps }
    }
    var monthlySteps: Int {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM"
        let currentMonth = formatter.string(from: Date())
        return records.filter { $0.date.hasPrefix(currentMonth) }.reduce(0) { $0 + $1.steps }
    }
    var authorizationText: String { isAuthorized ? "許可済み" : "未許可" }
    var sourceText: String { service.isHealthDataAvailable ? "HealthKit" : "未対応端末" }
    var liveUpdateText: String { service.isPedometerLiveUpdatesAvailable() ? "利用可能" : "未対応" }
    var lastSyncText: String {
        guard let lastSyncAt else { return "未同期" }
        return lastSyncAt.formatted(date: .abbreviated, time: .shortened)
    }

    func bootstrap() async {
        records = store.loadRecords()
        let syncState = store.loadSyncState()
        lastSyncAt = syncState.lastSuccessAt
        isAuthorized = syncState.permissionStatus == "granted"
        await syncNow()
    }

    func syncNow() async {
        guard service.isHealthDataAvailable else {
            errorMessage = "この端末ではHealthKitが利用できません。"
            do {
                try store.saveSyncState(SyncState(lastSyncAt: Date(), lastSuccessAt: nil, permissionStatus: "unavailable", healthSourceAvailable: false))
            } catch {}
            return
        }
        isSyncing = true
        errorMessage = nil
        do {
            try await service.requestAuthorization()
            isAuthorized = true
            let calendar = Calendar.current
            let startDate = calendar.date(byAdding: .day, value: -89, to: calendar.startOfDay(for: Date()))!
            let endDate = Date()
            let fresh = try await service.fetchDailyTotals(startDate: startDate, endDate: endDate)
            try store.replaceAllRecords(fresh)
            try store.saveSyncState(SyncState(lastSyncAt: Date(), lastSuccessAt: Date(), permissionStatus: "granted", healthSourceAvailable: true))
            records = store.loadRecords()
            lastSyncAt = Date()
        } catch {
            errorMessage = error.localizedDescription
            do {
                try store.saveSyncState(SyncState(lastSyncAt: Date(), lastSuccessAt: lastSyncAt, permissionStatus: "denied", healthSourceAvailable: true))
            } catch {}
        }
        isSyncing = false
    }

    private func lastNDates(_ count: Int) -> Set<String> {
        let calendar = Calendar.current
        return Set((0..<count).compactMap {
            guard let date = calendar.date(byAdding: .day, value: -$0, to: Date()) else { return nil }
            return ISO8601DateFormatter.dayFormatter.string(from: date)
        })
    }
}

private extension ISO8601DateFormatter {
    static let dayFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withFullDate]
        return formatter
    }()
}
