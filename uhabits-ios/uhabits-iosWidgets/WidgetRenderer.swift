import SwiftUI
import WidgetKit

// App only (excluded from the extension): `-renderWidgets <dir>` writes each widget as a PNG,
// light and dark, loading its data the way the extension does (app-group database).

enum WidgetRenderer {
    // iPhone 17 Pro widget sizes, in points
    static let sizes: [WidgetFamily: CGSize] = [
        .systemSmall: CGSize(width: 170, height: 170),
        .systemMedium: CGSize(width: 364, height: 170),
        .systemLarge: CGSize(width: 364, height: 382),
    ]

    @MainActor
    static func renderAll(to dir: String) {
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let habit = WidgetStore.habitEntry(id: nil).0
        let historyMedium = WidgetStore.habitEntry(id: nil, historySize: HistoryWidgetView.chartSize(for: sizes[.systemMedium]!)).0
        let historyLarge = WidgetStore.habitEntry(id: nil, historySize: HistoryWidgetView.chartSize(for: sizes[.systemLarge]!)).0
        let list = { (f: WidgetFamily) in WidgetStore.listEntry(limit: HabitListWidgetView.limit(for: f)).0 }
        widgetLog.info("render: habit=\(habit.habit?.name ?? habit.message ?? "?", privacy: .public)")

        let jobs: [(String, WidgetFamily, AnyView)] = [
            ("checkmark-small", .systemSmall, AnyView(CheckmarkWidgetView(entry: habit))),
            ("history-medium", .systemMedium, AnyView(HistoryWidgetView(entry: historyMedium))),
            ("history-large", .systemLarge, AnyView(HistoryWidgetView(entry: historyLarge))),
            ("score-small", .systemSmall, AnyView(ScoreWidgetView(entry: habit, family: .systemSmall))),
            ("score-medium", .systemMedium, AnyView(ScoreWidgetView(entry: habit, family: .systemMedium))),
            ("streak-small", .systemSmall, AnyView(StreakWidgetView(entry: habit, family: .systemSmall))),
            ("streak-medium", .systemMedium, AnyView(StreakWidgetView(entry: habit, family: .systemMedium))),
            ("list-medium", .systemMedium, AnyView(HabitListWidgetView(entry: list(.systemMedium)))),
            ("list-large", .systemLarge, AnyView(HabitListWidgetView(entry: list(.systemLarge)))),
        ]
        for (name, family, view) in jobs {
            for scheme in [ColorScheme.light, .dark] {
                let size = sizes[family]!
                // Same padding and background as the home screen's widget container
                let framed = view.padding(16).frame(width: size.width, height: size.height)
                    .background(Color(uiColor: .systemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    .environment(\.colorScheme, scheme)
                let renderer = ImageRenderer(content: framed)
                renderer.scale = 3
                let path = "\(dir)/\(name)-\(scheme == .dark ? "dark" : "light").png"
                if let data = renderer.uiImage?.pngData() { FileManager.default.createFile(atPath: path, contents: data) }
            }
        }
        print("[uhabits] rendered \(jobs.count * 2) widget images to \(dir)")
    }
}
