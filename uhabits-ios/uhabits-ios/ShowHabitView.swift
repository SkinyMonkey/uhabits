import SwiftUI
import class UhabitsCore.IosAppGraph
import class UhabitsCore.IosShowHabit
import class UhabitsCore.ShowHabitSnapshot

/// Owns the Kotlin IosShowHabit (shared ShowHabitPresenter) and the latest snapshot.
final class ShowHabitModel: ObservableObject {
    @Published private(set) var snap: ShowHabitSnapshot?
    @Published var numberText: String?  // non-nil while the number prompt is shown
    private(set) var helper: IosShowHabit?
    private var dark = false

    func start(graph: IosAppGraph, habitId: Int64, dark: Bool, onChanged: @escaping () -> Void) {
        self.dark = dark
        if helper == nil {
            helper = IosShowHabit(
                graph: graph, habitId: habitId,
                onChange: { [weak self] in self?.reload(); onChanged() },
                onNumberPrompt: { [weak self] value, _ in self?.numberText = String(format: "%g", value.doubleValue) }
            )
        }
        reload()
    }

    func setDark(_ dark: Bool) { self.dark = dark; reload() }
    func reload() { snap = helper?.snapshot(dark: dark) }

    func submitNumber() {
        if let text = numberText, let v = Double(text.replacingOccurrences(of: ",", with: ".")) {
            helper?.submitNumber(value: v, notes: "")
        }
        numberText = nil
    }

    deinit { helper?.close() }
}

struct ShowHabitView: View {
    let habitId: Int64

    @EnvironmentObject var store: HabitStore
    @Environment(\.colorScheme) private var scheme
    @StateObject private var model = ShowHabitModel()

    var body: some View {
        Group {
            if let s = model.snap, let helper = model.helper {
                ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 12) {
                        SubtitleCard(s: s)
                        if !s.notes.isEmpty { NotesCard(s: s) }
                        if s.isNumerical { TargetCard(s: s) } else { OverviewCard(s: s) }
                        ScoreCard(s: s, helper: helper)
                        BarCard(s: s, helper: helper)
                        HistoryCard(s: s, helper: helper).id("history")
                        StreakCard(s: s)
                        FrequencyCard(s: s)
                    }
                    .padding(12)
                }
                // Debug: `-showHabitScroll history` scrolls to the history card for screenshots
                .onAppear { if let id = UserDefaults.standard.string(forKey: "showHabitScroll") { proxy.scrollTo(id, anchor: .top) } }
                }
                .background(Color(hex: s.appBackground).ignoresSafeArea())
            } else {
                Text("Habit not found").foregroundStyle(.secondary)
            }
        }
        .navigationTitle(model.snap?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            model.start(graph: store.graph, habitId: habitId, dark: scheme == .dark) { [weak store] in store?.reload() }
        }
        .onChange(of: scheme) { model.setDark($0 == .dark) }
        .alert("Value", isPresented: Binding(get: { model.numberText != nil }, set: { if !$0 { model.numberText = nil } })) {
            TextField("Value", text: Binding(get: { model.numberText ?? "" }, set: { model.numberText = $0 }))
                .keyboardType(.decimalPad)
            Button("Cancel", role: .cancel) { model.numberText = nil }
            Button("Save") { model.submitNumber() }
        }
    }
}
