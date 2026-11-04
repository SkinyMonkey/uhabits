import SwiftUI
import class UhabitsCore.ColorRequest
import class UhabitsCore.ConfirmRequest
import class UhabitsCore.EntryRequest
import class UhabitsCore.HabitColor
import class UhabitsCore.HabitDraft
import class UhabitsCore.IosAppGraph
import class UhabitsCore.IosListHabits
import class UhabitsCore.KotlinLong
import class UhabitsCore.ListSnapshot
import class UhabitsCore.TextRequest

// Kotlin objects are NSObjects, so identity is enough for sheet(item:)
extension EntryRequest: Identifiable {}
extension TextRequest: Identifiable {}
extension ColorRequest: Identifiable {}
extension HabitDraft: Identifiable {}

extension HabitColor {
    /// Theme colour that follows light/dark mode, like Android's theme.color(paletteColor).
    var color: Color {
        let light = UIColor(Color(hex: self.light)), dark = UIColor(Color(hex: self.dark))
        return Color(UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

/// Publishes the list snapshot and the dialogs the shared behaviors ask for.
/// The snapshot is refreshed by IosListHabits' CommandRunner listener, never re-read by hand.
final class ListHabitsModel: ObservableObject {
    let core: IosListHabits
    @Published private(set) var snapshot: ListSnapshot?
    @Published var entry: EntryRequest?
    @Published var categoryInput: TextRequest?
    @Published var colorPicker: ColorRequest?
    @Published var deleteConfirm: ConfirmRequest?
    @Published var editing: HabitDraft?
    @Published var choosingType = false
    @Published private(set) var message: String?

    init(graph: IosAppGraph) {
        core = IosListHabits(graph: graph)
        snapshot = core.snapshot
        core.onChange = { [weak self] in self?.snapshot = $0 }
        core.onEntryPopup = { [weak self] in self?.entry = $0 }
        core.onCategoryInput = { [weak self] in self?.categoryInput = $0 }
        core.onColorPicker = { [weak self] in self?.colorPicker = $0 }
        core.onConfirmDelete = { [weak self] in self?.deleteConfirm = $0 }
        core.onEditHabit = { [weak self] in self?.editing = $0 }
        core.onSelectHabitType = { [weak self] in self?.choosingType = true }
        core.onMessage = { [weak self] in self?.show($0) }
    }

    /// Android's snackbar. ponytail: no undo, the core commands have no undo support
    private func show(_ text: String) {
        message = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            if self?.message == text { self?.message = nil }
        }
    }

    /// Runs a selection-menu action on the given habits.
    func withSelection(_ ids: [Int64], _ action: () -> Void) {
        core.setSelected(ids: ids.map { KotlinLong(value: $0) })
        action()
    }
}
