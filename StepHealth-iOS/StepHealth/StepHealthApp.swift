import SwiftUI

@main
struct StepHealthApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = StepDashboardViewModel()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                ContentView(viewModel: viewModel)
            }
            .task {
                await viewModel.bootstrap()
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    Task { await viewModel.syncNow() }
                }
            }
        }
    }
}

struct ContentView: View {
    @ObservedObject var viewModel: StepDashboardViewModel

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("今日の歩数")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(viewModel.todaySteps.formatted())
                        .font(.system(size: 42, weight: .bold, design: .rounded))
                    Text("端末のヘルスデータから日次総量を再取得して保存")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section("サマリー") {
                metricRow("総累計", value: viewModel.totalSteps)
                metricRow("直近7日", value: viewModel.weeklySteps)
                metricRow("今月", value: viewModel.monthlySteps)
            }

            Section("同期") {
                HStack {
                    Text("権限")
                    Spacer()
                    Text(viewModel.authorizationText)
                        .foregroundStyle(viewModel.isAuthorized ? .green : .orange)
                }
                HStack {
                    Text("データソース")
                    Spacer()
                    Text(viewModel.sourceText)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("歩数ライブ更新")
                    Spacer()
                    Text(viewModel.liveUpdateText)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Text("最終同期")
                    Spacer()
                    Text(viewModel.lastSyncText)
                        .foregroundStyle(.secondary)
                }
                if let error = viewModel.errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                }
                Button(viewModel.isSyncing ? "同期中..." : "今すぐ同期") {
                    Task { await viewModel.syncNow() }
                }
                .disabled(viewModel.isSyncing)
            }

            Section("日別履歴") {
                ForEach(viewModel.records) { record in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(record.date)
                            Text(record.source)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("\(record.steps.formatted()) 歩")
                    }
                }
            }
        }
        .navigationTitle("累計歩数")
    }

    private func metricRow(_ title: String, value: Int) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value.formatted())
                .font(.headline)
        }
    }
}
