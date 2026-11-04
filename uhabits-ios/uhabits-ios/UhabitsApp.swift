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

struct HabitListView: View {
    @EnvironmentObject var store: HabitStore
    @State private var creating = false

    var body: some View {
        NavigationStack {
            List(store.habits, id: \.id) { habit in
                Button { store.toggle(habit) } label: {
                    HStack {
                        Circle().fill(Color(hex: habit.colorHex)).frame(width: 12, height: 12)
                        Text(habit.name).foregroundStyle(Color(hex: habit.colorHex))
                        Spacer()
                        Image(systemName: habit.checkedToday ? "checkmark" : "xmark")
                            .foregroundStyle(habit.checkedToday ? Color(hex: habit.colorHex) : .secondary)
                    }
                }
                .accessibilityLabel("\(habit.name), \(habit.checkedToday ? "done" : "not done") today")
            }
            .overlay { if store.habits.isEmpty { Text("No habits yet").foregroundStyle(.secondary) } }
            .navigationTitle("Habits")
            .toolbar { Button { creating = true } label: { Image(systemName: "plus") }.accessibilityLabel("New habit") }
            .sheet(isPresented: $creating) { NewHabitView() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            store.reload() // picks up a new "today" after midnight
        }
    }
}

struct NewHabitView: View {
    @EnvironmentObject var store: HabitStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var colorIndex = 8

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(0..<20, id: \.self) { i in
                            Circle()
                                .fill(Color(hex: PaletteColor(paletteIndex: Int32(i)).toCsvColor()))
                                .frame(width: 32, height: 32)
                                .overlay { if i == colorIndex { Image(systemName: "checkmark").foregroundStyle(.white) } }
                                .onTapGesture { colorIndex = i }
                        }
                    }
                }
            }
            .navigationTitle("New habit")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.create(name: name.trimmingCharacters(in: .whitespaces), colorIndex: colorIndex)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

extension Color {
    /// Parses "#RRGGBB", the format of PaletteColor.toCsvColor().
    init(hex: String) {
        let v = UInt32(hex.dropFirst(), radix: 16) ?? 0
        self.init(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}
