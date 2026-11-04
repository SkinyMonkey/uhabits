import SwiftUI
// Import only what we use: the core also exports View, Color, etc., which clash with SwiftUI
import class UhabitsCore.HabitItem
import class UhabitsCore.IosAppGraph
import class UhabitsCore.PaletteColor

@main
struct UhabitsApp: App {
    @StateObject private var store = HabitStore()

    var body: some Scene {
        WindowGroup { HabitListView().environmentObject(store) }
    }
}

/// Thin wrapper over the Kotlin graph. All calls are synchronous; the list is re-read after each change.
final class HabitStore: ObservableObject {
    private let graph: IosAppGraph
    @Published private(set) var habits: [HabitItem] = []

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        // uhabits-core/assets/main is bundled as the folder reference "main" (migrations/, fonts/)
        graph = IosAppGraph(resourceRoot: Bundle.main.bundlePath + "/main", userRoot: docs)
        // Debug: `-seed` creates two habits and checks the first one, only when the DB is empty
        if CommandLine.arguments.contains("-seed"), graph.habits().isEmpty {
            graph.createHabit(name: "Meditate", colorIndex: 8)
            graph.createHabit(name: "Exercise", colorIndex: 0)
            graph.toggleToday(habitId: graph.habits()[0].id)
        }
        reload()
        print("[uhabits] db=\(docs) habits=\(habits.map { "\($0.name):\($0.checkedToday)" })")
    }

    func reload() { habits = graph.habits() }
    func toggle(_ habit: HabitItem) { graph.toggleToday(habitId: habit.id); reload() }
    func create(name: String, colorIndex: Int) { graph.createHabit(name: name, colorIndex: Int32(colorIndex)); reload() }
}
