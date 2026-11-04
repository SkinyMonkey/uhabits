import SwiftUI
import class UhabitsCore.Entry
import class UhabitsCore.EntryRequest
import class UhabitsCore.HabitColor
import class UhabitsCore.HabitRow

private let YES_MANUAL = Entry.companion.YES_MANUAL
private let YES_AUTO = Entry.companion.YES_AUTO
private let NO = Entry.companion.NO_VALUE
private let SKIP = Entry.companion.SKIP
private let UNKNOWN = Entry.companion.UNKNOWN

/// One day of one habit, drawn like Android's CheckmarkButtonView / NumberButtonView.
struct DayButton: View {
    @EnvironmentObject var model: ListHabitsModel
    let habit: HabitRow
    let day: Int32

    var body: some View {
        content
            .frame(width: DayLayout.buttonWidth, height: 48)
            .overlay(alignment: .topTrailing) {
                if habit.hasNotes(day: day) {
                    Circle().fill(habit.color.color).frame(width: 5, height: 5).padding(6)
                }
            }
            .contentShape(Rectangle())
            // One exclusive gesture: separate onTapGesture/onLongPressGesture in a List row never fire the long press
            .gesture(
                LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    model.core.onLongPressDay(habitId: habit.id, day: day)
                }
                .exclusively(before: TapGesture().onEnded { model.core.onClickDay(habitId: habit.id, day: day) })
            )
            .accessibilityElement()
            .accessibilityLabel(accessibilityText)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: "Edit entry") { model.core.onEditDay(habitId: habit.id, day: day) }
            .accessibilityIdentifier("day.\(habit.name).\(day)")
    }

    private var questionMarks: Bool { model.snapshot?.areQuestionMarksEnabled == true }
    private var value: Int32 { habit.value(day: day) }

    @ViewBuilder
    private var content: some View {
        if habit.isNumerical {
            let color: Color = habit.completed(day: day) ? habit.color.color : value < 0 ? Color(.systemGray4) : .secondary
            VStack(spacing: 0) {
                if value == SKIP {
                    Image(systemName: "minus").font(.footnote.bold())
                } else if value < 0 && questionMarks {
                    Image(systemName: "questionmark").font(.caption.bold())
                } else {
                    Text(value < 0 ? "0" : habit.numberLabel(day: day)).font(.footnote.bold())
                        .minimumScaleFactor(0.6)
                }
                Text(habit.unit).font(.caption2).lineLimit(1).minimumScaleFactor(0.6)
            }
            .foregroundStyle(color)
        } else {
            let color: Color = switch value {
            case YES_MANUAL, YES_AUTO, SKIP: habit.color.color
            case NO: questionMarks ? .secondary : Color(.systemGray4)
            default: Color(.systemGray4)
            }
            let symbol = switch value {
            case SKIP: "minus"
            case NO: "xmark"
            case UNKNOWN: questionMarks ? "questionmark" : "xmark"
            default: "checkmark"
            }
            Image(systemName: symbol)
                .font(.system(size: value == UNKNOWN && questionMarks ? 13 : 15, weight: .bold))
                // ponytail: Android draws YES_AUTO as a hollow check; a faded check reads the same
                .foregroundStyle(value == YES_AUTO ? color.opacity(0.45) : color)
        }
    }

    private var accessibilityText: String {
        let yesNo = switch value {
        case YES_MANUAL: "done"
        case YES_AUTO: "not needed"
        case SKIP: "skipped"
        case NO: "not done"
        default: "unknown"
        }
        let state = !habit.isNumerical ? yesNo : value < 0 ? "no value" : "\(habit.numberLabel(day: day)) \(habit.unit)"
        let when = day == 0 ? "today" : day == 1 ? "yesterday" : "\(day) days ago"
        return "\(habit.name), \(when), \(state)"
    }
}

/// All dialogs the shared behaviors can ask for (Android's ListHabitsScreen show* methods).
extension View {
    func listDialogs(model: ListHabitsModel, onDone: @escaping () -> Void) -> some View {
        modifier(ListDialogs(model: model, onDone: onDone))
    }
}

private struct ListDialogs: ViewModifier {
    @ObservedObject var model: ListHabitsModel
    let onDone: () -> Void
    @State private var categoryText = ""

    func body(content: Content) -> some View {
        content
            .sheet(item: $model.entry) { EntrySheet(request: $0).presentationDetents([.medium]) }
            .sheet(item: $model.editing) { EditHabitView(draft: $0).environmentObject(model) }
            .sheet(item: $model.colorPicker) { request in
                NavigationStack {
                    ColorGrid(selected: Int(request.current)) {
                        request.pick(index: Int32($0))
                        model.colorPicker = nil
                        onDone()
                    }
                    .padding()
                    .navigationTitle("Change color")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { model.colorPicker = nil } } }
                }
                .presentationDetents([.medium])
            }
            .alert("Category", isPresented: Binding(get: { model.categoryInput != nil },
                                                    set: { if !$0 { model.categoryInput = nil } })) {
                TextField("Category", text: $categoryText)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    model.categoryInput?.confirm(text: categoryText)
                    onDone()
                }
            } message: {
                Text("Leave empty to remove the category.")
            }
            .onReceive(model.$categoryInput) { categoryText = $0?.current ?? "" }
            .alert(deleteTitle, isPresented: Binding(get: { model.deleteConfirm != nil },
                                                     set: { if !$0 { model.deleteConfirm = nil } })) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) {
                    model.deleteConfirm?.confirm()
                    onDone()
                }
            } message: {
                Text("This cannot be undone.")
            }
            .confirmationDialog("New habit", isPresented: $model.choosingType, titleVisibility: .visible) {
                Button("Yes or No") { model.editing = model.core.createDraft(isNumerical: false) }
                Button("Measurable") { model.editing = model.core.createDraft(isNumerical: true) }
            } message: {
                Text("Yes or No: e.g. did you wake up early today? Measurable: e.g. how many miles did you run?")
            }
    }

    private var deleteTitle: String {
        let n = model.deleteConfirm?.count ?? 1
        return n == 1 ? "Delete habit?" : "Delete \(n) habits?"
    }
}

/// Android's CheckmarkDialog / NumberDialog: pick a value and edit the notes.
struct EntrySheet: View {
    let request: EntryRequest
    @Environment(\.dismiss) private var dismiss
    @State private var number = ""
    @State private var notes = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                if request.isNumerical {
                    Section {
                        HStack {
                            TextField("Value", text: $number).keyboardType(.decimalPad).focused($focused)
                                .accessibilityIdentifier("entry.value")
                                .font(.title2.bold())
                            Text(request.unit).foregroundStyle(.secondary)
                        }
                    }
                } else {
                    Section { checkmarkButtons }
                }
                Section("Notes") {
                    TextField("Notes", text: $notes, axis: .vertical).lineLimit(2...5).accessibilityIdentifier("entry.notes")
                }
            }
            .navigationTitle(request.habitName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).bold() }
                if request.isNumerical {
                    ToolbarItemGroup(placement: .bottomBar) {
                        if request.isSkipEnabled {
                            Button("Skip") { request.saveNumberSkip(notes: trimmedNotes); dismiss() }
                        }
                        if request.areQuestionMarksEnabled {
                            Button("Clear") { request.saveNumberUnknown(notes: trimmedNotes); dismiss() }
                        }
                    }
                }
            }
        }
        .tint(request.color.color)
        .onAppear {
            notes = request.notes
            number = request.number < 0.01 ? "0" : request.number.formatted(.number.precision(.fractionLength(0...2)))
            focused = request.isNumerical
        }
    }

    private var trimmedNotes: String { notes.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var checkmarkButtons: some View {
        HStack {
            choice("Yes", "checkmark", YES_MANUAL)
            choice("No", "xmark", NO)
            if request.isSkipEnabled { choice("Skip", "minus", SKIP) }
            if request.areQuestionMarksEnabled { choice("Unknown", "questionmark", UNKNOWN) }
        }
        .buttonStyle(.borderless)
    }

    private func choice(_ title: String, _ symbol: String, _ value: Int32) -> some View {
        Button {
            request.saveCheckmark(value: value, notes: trimmedNotes)
            dismiss()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.title2.bold())
                Text(title).font(.caption)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .foregroundStyle(value == YES_MANUAL ? request.color.color : .secondary)
            .background(request.value == value ? Color(.systemGray5) : .clear, in: RoundedRectangle(cornerRadius: 10))
        }
        .accessibilityLabel(title)
        .accessibilityIdentifier("entry.\(title)")
    }

    private func save() {
        if request.isNumerical {
            let parsed = Double(number.replacingOccurrences(of: Locale.current.decimalSeparator ?? ".", with: "."))
            // Empty means "no value", as in Android's NumberDialog
            if number.isEmpty { request.saveNumberUnknown(notes: trimmedNotes) }
            else { request.saveNumber(value: parsed ?? request.number, notes: trimmedNotes) }
        } else {
            request.saveCheckmark(value: request.value, notes: trimmedNotes)
        }
        dismiss()
    }
}

/// The 20 palette colours, as in Android's ColorPickerDialog.
struct ColorGrid: View {
    let selected: Int
    let onPick: (Int) -> Void

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 14) {
            ForEach(0..<20, id: \.self) { i in
                Button { onPick(i) } label: {
                    Circle()
                        .fill(HabitColor(index: Int32(i)).color)
                        .frame(width: 44, height: 44)
                        .overlay { if i == selected { Image(systemName: "checkmark").font(.headline).foregroundStyle(.white) } }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Color \(i + 1)\(i == selected ? ", selected" : "")")
            }
        }
    }
}
