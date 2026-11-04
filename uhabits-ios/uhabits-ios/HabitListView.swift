import SwiftUI
import class UhabitsCore.HabitRow
import class UhabitsCore.ListRow
import class UhabitsCore.ListSnapshot

/// Main screen, the counterpart of Android's ListHabitsActivity.
struct HabitListView: View {
    @EnvironmentObject var model: ListHabitsModel
    @State private var path: [Int64] = []
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()

    private var editing: Bool { editMode.isEditing }

    var body: some View {
        NavigationStack(path: $path) {
            GeometryReader { geo in
                let n = DayLayout.buttonCount(width: geo.size.width)
                list(n: editing ? 0 : n)
                    .safeAreaInset(edge: .top, spacing: 0) {
                        if !editing, let snap = model.snapshot, snap.hasHabits { DayHeader(snapshot: snap, count: n) }
                    }
            }
            .navigationTitle("Habits")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: Int64.self) { ShowHabitView(habitId: $0) }
            .toolbar { toolbar }
            .environment(\.editMode, $editMode)
            .overlay { emptyState }
            .overlay(alignment: .bottom) { toast }
        }
        .listDialogs(model: model, onDone: endEditing)
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            model.core.refresh() // picks up a new "today" after midnight
        }
        .onAppear(perform: handleDebugArguments)
    }

    private func list(n: Int) -> some View {
        List(selection: $selection) {
            ForEach(model.snapshot?.rows ?? [], id: \.key) { row in
                if let habit = row.habit {
                    HabitRowView(habit: habit, count: n, reversed: model.snapshot?.isSequenceReversed ?? false,
                                 onOpen: { path.append(habit.id) },
                                 actions: { actions(for: [habit]) })
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: DayLayout.trailing))
                } else if let category = row.category {
                    Text(category)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                        .padding(.top, 8)
                        .moveDisabled(true)
                        .contextMenu {
                            Button { model.core.behavior.onRenameCategory(category: category) } label: {
                                Label("Rename category", systemImage: "pencil")
                            }
                        }
                }
            }
            .onMove(perform: model.snapshot?.isSortable == true ? move : nil)
        }
        .listStyle(.plain)
    }

    private func move(from: IndexSet, to: Int) {
        guard let first = from.first else { return }
        model.core.moveRow(from: Int32(first), to: Int32(to))
    }

    // MARK: Selection actions (ListHabitsSelectionMenuBehavior)

    private var selectedHabits: [HabitRow] {
        (model.snapshot?.rows ?? []).filter { selection.contains($0.key) }.compactMap(\.habit)
    }

    @ViewBuilder
    private func actions(for habits: [HabitRow]) -> some View {
        let ids = habits.map(\.id)
        let sel = model.core.selection
        if habits.count == 1 {
            Button(action: run(ids) { sel.onEditHabits() }) { Label("Edit", systemImage: "pencil") }
        }
        Button(action: run(ids) { sel.onChangeColor() }) { Label("Change color", systemImage: "paintpalette") }
        Button(action: run(ids) { sel.onChangeCategory() }) { Label("Change category", systemImage: "folder") }
        if habits.allSatisfy(\.isArchived) {
            Button(action: run(ids) { sel.onUnarchiveHabits(); endEditing() }) {
                Label("Unarchive", systemImage: "tray.and.arrow.up")
            }
        } else if !habits.contains(where: \.isArchived) {
            Button(action: run(ids) { sel.onArchiveHabits(); endEditing() }) {
                Label("Archive", systemImage: "archivebox")
            }
        }
        Button(role: .destructive, action: run(ids) { sel.onDeleteHabits() }) {
            Label("Delete", systemImage: "trash")
        }
    }

    private func run(_ ids: [Int64], _ action: @escaping () -> Void) -> () -> Void {
        { model.withSelection(ids, action) }
    }

    private func endEditing() {
        selection.removeAll()
        editMode = .inactive
    }

    // MARK: Toolbar and menu (ListHabitsMenuBehavior)

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if editing {
                Button("Done", action: endEditing).bold()
            } else {
                Button { model.core.menu.onCreateHabit() } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New habit")
                optionsMenu
            }
        }
        ToolbarItemGroup(placement: .bottomBar) {
            if editing {
                let habits = selectedHabits
                Menu { actions(for: habits) } label: { Text(habits.isEmpty ? "Select habits" : "Actions (\(habits.count))") }
                    .disabled(habits.isEmpty)
                Spacer()
                if model.snapshot?.isSortable == false {
                    Text("Sort manually to reorder").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var optionsMenu: some View {
        let snap = model.snapshot
        let menu = model.core.menu
        return Menu {
            Button { editMode = .active } label: { Label("Select / reorder", systemImage: "checklist") }
            Menu {
                sortButton("Manually", snap, asc: "BY_POSITION", desc: nil) { menu.onSortByManually() }
                sortButton("By name", snap, asc: "BY_NAME_ASC", desc: "BY_NAME_DESC") { menu.onSortByName() }
                sortButton("By color", snap, asc: "BY_COLOR_ASC", desc: "BY_COLOR_DESC") { menu.onSortByColor() }
                sortButton("By score", snap, asc: "BY_SCORE_DESC", desc: "BY_SCORE_ASC") { menu.onSortByScore() }
                sortButton("By status", snap, asc: "BY_STATUS_ASC", desc: "BY_STATUS_DESC") { menu.onSortByStatus() }
            } label: { Label("Sort", systemImage: "arrow.up.arrow.down") }
            Toggle("Hide archived", isOn: Binding(get: { !(snap?.showArchived ?? false) },
                                                  set: { _ in menu.onToggleShowArchived() }))
            Toggle(snap?.hideEnteredLabel == true ? "Hide entered" : "Hide completed",
                   isOn: Binding(get: { !(snap?.showCompleted ?? true) }, set: { _ in menu.onToggleShowCompleted() }))
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Options")
    }

    /// Like Android's ListHabitsMenu.updateArrows: the active order shows an arrow, tapping again reverses it.
    private func sortButton(_ title: String, _ snap: ListSnapshot?, asc: String, desc: String?,
                            action: @escaping () -> Void) -> some View {
        let order = snap?.primaryOrder
        return Button(action: action) {
            if order == asc {
                Label(title, systemImage: desc == nil ? "checkmark" : "arrow.down")
            } else if order == desc {
                Label(title, systemImage: "arrow.up")
            } else {
                Text(title)
            }
        }
    }

    // MARK: Overlays

    @ViewBuilder
    private var emptyState: some View {
        if let snap = model.snapshot, snap.rows.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: snap.hasHabits ? "checkmark.circle" : "list.bullet").font(.largeTitle)
                Text(snap.hasHabits ? "All habits are done" : "You have no active habits")
            }
            .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var toast: some View {
        if let message = model.message {
            Text(message)
                .font(.subheadline)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 24)
                .transition(.opacity)
        }
    }

    /// Debug launch arguments for screenshots: -editHabit <id>, -newHabit, -newNumericHabit, -entry <id>.
    private func handleDebugArguments() {
        let args = CommandLine.arguments
        func value(_ flag: String) -> Int64? {
            args.firstIndex(of: flag).flatMap { args.indices.contains($0 + 1) ? Int64(args[$0 + 1]) : nil }
        }
        if let id = value("-editHabit") { model.editing = model.core.draft(habitId: id) }
        if args.contains("-newHabit") { model.editing = model.core.createDraft(isNumerical: false) }
        if args.contains("-newNumericHabit") { model.editing = model.core.createDraft(isNumerical: true) }
        if let id = value("-entry") { model.core.onEditDay(habitId: id, day: 0) }
    }
}

// MARK: - Row

enum DayLayout {
    static let buttonWidth: CGFloat = 44
    static let trailing: CGFloat = 4
    /// As many day buttons as fit next to the ring and a readable name, like Android's HabitCardListView.
    static func buttonCount(width: CGFloat) -> Int {
        let room = width - 16 - trailing - 28 - 120
        return max(1, min(Int(room / buttonWidth), 14))
    }
}

/// Days shown, left to right. Day 0 is today; Android puts today first unless the sequence is reversed.
private func days(_ count: Int, reversed: Bool) -> [Int32] {
    let d = (0..<count).map(Int32.init)
    return reversed ? d.reversed() : d
}

struct DayHeader: View {
    let snapshot: ListSnapshot
    let count: Int

    var body: some View {
        HStack(spacing: 0) {
            Spacer()
            ForEach(days(count, reversed: snapshot.isSequenceReversed), id: \.self) { d in
                VStack(spacing: 1) {
                    Text(snapshot.weekdayHeaders[Int(d)])
                    Text(snapshot.dayHeaders[Int(d)])
                }
                .font(.caption2.weight(.bold))
                .foregroundStyle(.secondary)
                .frame(width: DayLayout.buttonWidth)
            }
        }
        .padding(.trailing, DayLayout.trailing)
        .padding(.vertical, 4)
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityHidden(true)
    }
}

struct HabitRowView<Actions: View>: View {
    @EnvironmentObject var model: ListHabitsModel
    @Environment(\.editMode) private var editMode
    let habit: HabitRow
    let count: Int
    let reversed: Bool
    let onOpen: () -> Void
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        let editing = editMode?.wrappedValue.isEditing == true
        HStack(spacing: 0) {
            HStack(spacing: 10) {
                ScoreRing(score: habit.score, color: habit.color.color)
                Text(habit.name)
                    .font(.body)
                    .foregroundStyle(habit.color.color)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }
            .opacity(habit.isArchived ? 0.5 : 1)
            .frame(minHeight: 48)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            .contextMenu { actions() }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint("Opens the habit")
            ForEach(days(count, reversed: reversed), id: \.self) { d in
                DayButton(habit: habit, day: d)
            }
        }
        .allowsHitTesting(!editing) // in edit mode the List handles taps (selection)
    }
}

struct ScoreRing: View {
    let score: Double
    let color: Color

    var body: some View {
        ZStack {
            Circle().stroke(Color(.systemGray5), lineWidth: 4)
            Circle().trim(from: 0, to: max(0, min(1, score)))
                .stroke(color, style: StrokeStyle(lineWidth: 4))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 18, height: 18)
        .accessibilityLabel("Score \(Int((score * 100).rounded()))%")
    }
}
