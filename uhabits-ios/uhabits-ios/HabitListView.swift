import SwiftUI
import class UhabitsCore.HabitItem

struct HabitListView: View {
    @EnvironmentObject var store: HabitStore
    @State private var creating = false

    var body: some View {
        NavigationStack {
            List(store.habits, id: \.id) { habit in
                HStack {
                    NavigationLink { ShowHabitView(habitId: habit.id) } label: {
                        HStack {
                            Circle().fill(Color(hex: habit.colorHex)).frame(width: 12, height: 12)
                            Text(habit.name).foregroundStyle(Color(hex: habit.colorHex))
                        }
                    }
                    Button { store.toggle(habit) } label: {
                        Image(systemName: habit.checkedToday ? "checkmark" : "xmark")
                            .foregroundStyle(habit.checkedToday ? Color(hex: habit.colorHex) : .secondary)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("\(habit.name), \(habit.checkedToday ? "done" : "not done") today")
                }
            }
            .overlay { if store.habits.isEmpty { Text("No habits yet").foregroundStyle(.secondary) } }
            .navigationTitle("Habits")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { creating = true } label: { Image(systemName: "plus") }.accessibilityLabel("New habit")
                }
            }
            .sheet(isPresented: $creating) { NewHabitView() }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            store.reload() // picks up a new "today" after midnight
        }
    }
}
