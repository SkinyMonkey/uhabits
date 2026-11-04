import SwiftUI
import WidgetKit
import os
// Import only what we use: the core also exports View, Color, etc., which clash with SwiftUI
import class UhabitsCore.IosWidgets
import class UhabitsCore.WidgetHabit

// Shared by the widget extension and the app (the app renders these views for `-renderWidgets`).

let widgetLog = Logger(subsystem: "org.isoron.uhabits.ios", category: "widgets")

/// Value copy of the Kotlin WidgetHabit, so timeline entries do not hold Kotlin objects.
struct HabitData: Identifiable {
    let id: Int64
    let name: String
    let lightColor: String
    let darkColor: String
    let isNumerical: Bool
    let value: Int32
    let isCompleted: Bool
    let numberLabel: String
    let unit: String
    let score: Double
    let scores: [Double]
    let streaks: [(start: String, end: String, length: Int)]

    init(_ h: WidgetHabit) {
        id = h.id
        name = h.name
        lightColor = h.color.light
        darkColor = h.color.dark
        isNumerical = h.isNumerical
        value = h.todayValue
        isCompleted = h.isCompletedToday
        numberLabel = h.numberLabel
        unit = h.unit
        score = h.score
        scores = h.scores.map { $0.doubleValue }
        streaks = h.streaks.map { ($0.start, $0.end, Int($0.length)) }
    }

    func color(_ scheme: ColorScheme) -> Color { Color(widgetHex: scheme == .dark ? darkColor : lightColor) }
}

struct HabitEntry: TimelineEntry {
    var date = Date()
    /// Single-habit widgets; nil when nothing is configured or the database is missing.
    var habit: HabitData?
    /// List widget.
    var habits: [HabitData] = []
    /// History widget: the shared HistoryChart, rendered for both appearances.
    var history: (light: UIImage, dark: UIImage)?
    /// Shown instead of the content, e.g. before the app ever ran.
    var message: String?
}

enum WidgetStore {
    static let groupId = IosWidgets.companion.GROUP_ID

    /// The app's bundled uhabits-core/assets/main (fonts, migrations). The extension lives in App.app/PlugIns/.
    static var resourceRoot: String {
        var url = Bundle.main.bundleURL
        if url.pathExtension == "appex" { url = url.deletingLastPathComponent().deletingLastPathComponent() }
        return url.appendingPathComponent("main").path
    }

    /// Opens the shared database for one request, or nil (with a reason) when it is not there yet.
    /// Never creates it: the app must first move its Documents database into the group container.
    static func with<T>(_ body: (IosWidgets) -> T) -> Result<T, WidgetError> {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)?.path else {
            widgetLog.error("no app-group container for \(groupId, privacy: .public)")
            return .failure(.noGroup)
        }
        guard FileManager.default.fileExists(atPath: dir + "/uhabits.db") else {
            widgetLog.info("no database in \(dir, privacy: .public)")
            return .failure(.noDatabase)
        }
        let widgets = IosWidgets(resourceRoot: resourceRoot, userRoot: dir, defaults: IosWidgets.companion.groupDefaults(groupId: groupId))
        defer { widgets.close() }
        return .success(body(widgets))
    }

    enum WidgetError: Error {
        case noGroup, noDatabase
        var message: String { self == .noGroup ? "Widgets unavailable" : "Open Loop to get started" }
    }

    /// Next "today" boundary: midnight, shifted by the midnight-delay setting.
    static func nextRefresh(after date: Date = Date(), delayHours: Int) -> Date {
        let cal = Calendar.current
        let shifted = cal.date(byAdding: .hour, value: -delayHours, to: date)!
        let midnight = cal.startOfDay(for: cal.date(byAdding: .day, value: 1, to: shifted)!)
        return cal.date(byAdding: .hour, value: delayHours, to: midnight)!
    }

    // MARK: Entries

    static func habitEntry(id: Int64?, historySize: CGSize? = nil) -> (HabitEntry, Date) {
        let result = with { w -> (HabitEntry, Date) in
            let habit = id.flatMap { w.habit(id: $0) } ?? w.habits().first
            var entry = HabitEntry(habit: habit.map(HabitData.init))
            if let habit, let size = historySize {
                let render = { (dark: Bool) in
                    w.renderHistory(id: habit.id, width: size.width, height: size.height, scale: 3, dark: dark)
                }
                if let light = render(false), let dark = render(true) { entry.history = (light, dark) }
            }
            if habit == nil { entry.message = "No habits yet" }
            widgetLog.info("habit entry: \(habit?.name ?? "none", privacy: .public)")
            return (entry, nextRefresh(delayHours: Int(w.midnightDelayHours)))
        }
        return unwrap(result)
    }

    static func listEntry(limit: Int) -> (HabitEntry, Date) {
        let result = with { w -> (HabitEntry, Date) in
            let habits = w.habits().prefix(limit).map(HabitData.init)
            widgetLog.info("list entry: \(habits.count) habit(s)")
            var entry = HabitEntry(habits: Array(habits))
            if habits.isEmpty { entry.message = "No habits yet" }
            return (entry, nextRefresh(delayHours: Int(w.midnightDelayHours)))
        }
        return unwrap(result)
    }

    private static func unwrap(_ r: Result<(HabitEntry, Date), WidgetError>) -> (HabitEntry, Date) {
        switch r {
        case .success(let v): return v
        case .failure(let e): return (HabitEntry(message: e.message), nextRefresh(delayHours: 0))
        }
    }
}

extension Color {
    init(widgetHex hex: String) {
        let v = UInt64(hex.dropFirst(), radix: 16) ?? 0
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
}
