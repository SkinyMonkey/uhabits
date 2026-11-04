import SwiftUI

/// Placeholder; replaced by the show-habit screen (overview, charts, history).
struct ShowHabitView: View {
    let habitId: Int64

    var body: some View {
        Text("Habit \(habitId)").navigationTitle("Habit")
    }
}
