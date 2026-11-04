import AppIntents
import SwiftUI
import WidgetKit

// Extension only: the widget bundle, its intents and timeline providers.
// Views and data loading are in WidgetViews.swift / WidgetData.swift, which the app also compiles.

@main
struct UhabitsWidgets: WidgetBundle {
    var body: some Widget {
        CheckmarkWidget()
        HistoryWidget()
        ScoreWidget()
        StreakWidget()
        HabitListWidget()
    }
}

// MARK: - Intents

struct HabitEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Habit"
    static var defaultQuery = HabitQuery()
    let id: Int
    let name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct HabitQuery: EntityQuery {
    private func all() -> [HabitEntity] {
        (try? WidgetStore.with { $0.habits().map { HabitEntity(id: Int($0.id), name: $0.name) } }.get()) ?? []
    }
    func entities(for identifiers: [Int]) async throws -> [HabitEntity] { all().filter { identifiers.contains($0.id) } }
    func suggestedEntities() async throws -> [HabitEntity] { all() }
    func defaultResult() async -> HabitEntity? { all().first }
}

struct SelectHabitIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Select habit"
    static var description = IntentDescription("Choose the habit this widget shows.")
    @Parameter(title: "Habit") var habit: HabitEntity?
}

/// Toggles today's checkmark through the core (WidgetBehavior.onToggleRepetition), in the extension process.
struct ToggleHabitIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle habit"
    static var isDiscoverable = false
    @Parameter(title: "Habit") var habitId: Int

    init() {}
    init(habitId: Int) { self.habitId = habitId }

    @MainActor
    func perform() async throws -> some IntentResult {
        let wrote = (try? WidgetStore.with { $0.toggle(id: Int64(habitId)) }.get()) ?? false
        widgetLog.info("toggle \(habitId): wrote=\(wrote)")
        // The tapped widget reloads by itself; others may show the same habit
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

// MARK: - Providers

/// One entry per day; the next timeline starts at (delayed) midnight. App changes call reloadAllTimelines.
struct HabitProvider: AppIntentTimelineProvider {
    var history = false

    func placeholder(in context: Context) -> HabitEntry { HabitEntry(message: "Loop") }

    func snapshot(for configuration: SelectHabitIntent, in context: Context) async -> HabitEntry {
        load(configuration, context).0
    }

    func timeline(for configuration: SelectHabitIntent, in context: Context) async -> Timeline<HabitEntry> {
        let (entry, next) = load(configuration, context)
        return Timeline(entries: [entry], policy: .after(next))
    }

    private func load(_ c: SelectHabitIntent, _ context: Context) -> (HabitEntry, Date) {
        WidgetStore.habitEntry(
            id: c.habit.map { Int64($0.id) },
            historySize: history ? HistoryWidgetView.chartSize(for: context.displaySize) : nil
        )
    }
}

struct ListProvider: TimelineProvider {
    func placeholder(in context: Context) -> HabitEntry { HabitEntry(message: "Loop") }

    func getSnapshot(in context: Context, completion: @escaping (HabitEntry) -> Void) {
        completion(WidgetStore.listEntry(limit: HabitListWidgetView.limit(for: context.family)).0)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HabitEntry>) -> Void) {
        let (entry, next) = WidgetStore.listEntry(limit: HabitListWidgetView.limit(for: context.family))
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

// MARK: - Widgets

private extension View {
    func widgetBackground() -> some View { containerBackground(.background, for: .widget) }
}

struct CheckmarkWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "checkmark", intent: SelectHabitIntent.self, provider: HabitProvider()) {
            CheckmarkWidgetView(entry: $0).widgetBackground()
        }
        .configurationDisplayName("Checkmark")
        .description("Today's checkmark for a habit. Tap to toggle.")
        .supportedFamilies([.systemSmall])
    }
}

struct HistoryWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "history", intent: SelectHabitIntent.self, provider: HabitProvider(history: true)) {
            HistoryWidgetView(entry: $0).widgetBackground()
        }
        .configurationDisplayName("History")
        .description("The history calendar of a habit.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

private struct FamilyView<Content: View>: View {
    @Environment(\.widgetFamily) private var family
    let content: (WidgetFamily) -> Content
    var body: some View { content(family) }
}

struct ScoreWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "score", intent: SelectHabitIntent.self, provider: HabitProvider()) { entry in
            FamilyView { ScoreWidgetView(entry: entry, family: $0) }.widgetBackground()
        }
        .configurationDisplayName("Score")
        .description("The habit strength over time.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct StreakWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "streak", intent: SelectHabitIntent.self, provider: HabitProvider()) { entry in
            FamilyView { StreakWidgetView(entry: entry, family: $0) }.widgetBackground()
        }
        .configurationDisplayName("Best streaks")
        .description("The longest streaks of a habit.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct HabitListWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "list", provider: ListProvider()) {
            HabitListWidgetView(entry: $0).widgetBackground()
        }
        .configurationDisplayName("Habits")
        .description("Today's checkmarks for your habits. Tap to toggle.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}
