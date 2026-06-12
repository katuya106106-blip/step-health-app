import Foundation

struct DailyStepRecord: Codable, Identifiable, Hashable {
    var id: String { date }
    let date: String
    let steps: Int
    let source: String
    let syncedAt: Date
}

struct SyncState: Codable {
    let lastSyncAt: Date?
    let lastSuccessAt: Date?
    let permissionStatus: String
    let healthSourceAvailable: Bool
}
