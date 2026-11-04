import SwiftUI
import class UhabitsCore.HabitColor
import class UhabitsCore.HabitDraft

/// Create/edit screen for yes/no and measurable habits, the counterpart of Android's EditHabitActivity.
/// Saving runs CreateHabitCommand/EditHabitCommand through the CommandRunner (HabitDraft.save).
struct EditHabitView: View {
    let draft: HabitDraft
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var question: String
    @State private var notes: String
    @State private var category: String
    @State private var colorIndex: Int
    @State private var frequency: FrequencyChoice
    @State private var x: Int
    @State private var y: Int
    @State private var target: String
    @State private var targetAtMost: Bool
    @State private var unit: String
    @State private var hasReminder: Bool
    @State private var reminderTime: Date
    @State private var reminderDays: Int
    @State private var pickingColor = false

    init(draft: HabitDraft) {
        self.draft = draft
        _name = State(initialValue: draft.name)
        _question = State(initialValue: draft.question)
        _notes = State(initialValue: draft.notes)
        _category = State(initialValue: draft.category)
        _colorIndex = State(initialValue: Int(draft.colorIndex))
        let (f, x, y) = FrequencyChoice.from(num: Int(draft.freqNum), den: Int(draft.freqDen))
        _frequency = State(initialValue: draft.isNumerical ? FrequencyChoice.numerical(den: Int(draft.freqDen)) : f)
        _x = State(initialValue: x)
        _y = State(initialValue: y)
        _target = State(initialValue: draft.isNew ? "" : draft.targetValue.formatted(.number.grouping(.never)))
        _targetAtMost = State(initialValue: draft.targetAtMost)
        _unit = State(initialValue: draft.unit)
        _hasReminder = State(initialValue: draft.hasReminder)
        let time = Calendar.current.date(from: DateComponents(hour: Int(draft.reminderHour), minute: Int(draft.reminderMinute)))
        _reminderTime = State(initialValue: time ?? Date())
        _reminderDays = State(initialValue: Int(draft.reminderDays))
    }

    private var color: Color { HabitColor(index: Int32(colorIndex)).color }
    private var targetValue: Double? { Double(target.replacingOccurrences(of: ",", with: ".")) }
    private var isValid: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && (!draft.isNumerical || targetValue != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField(draft.isNumerical ? "Name (e.g. Run)" : "Name (e.g. Exercise)", text: $name)
                            .font(.headline)
                            .accessibilityIdentifier("edit.name")
                        Button { pickingColor = true } label: {
                            Circle().fill(color).frame(width: 28, height: 28)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Color")
                    }
                    TextField(draft.isNumerical ? "Question (e.g. How many miles did you run today?)"
                                                : "Question (e.g. Did you exercise today?)",
                              text: $question, axis: .vertical)
                }

                if draft.isNumerical { measurableSection } else { frequencySection }

                Section("Reminder") {
                    Toggle("Remind me", isOn: $hasReminder.animation())
                    if hasReminder {
                        DatePicker("Time", selection: $reminderTime, displayedComponents: .hourAndMinute)
                        WeekdayPicker(packed: $reminderDays, tint: color)
                    }
                }

                Section("Category") {
                    TextField("Category (optional)", text: $category)
                }

                Section("Notes") {
                    TextField("Notes (optional)", text: $notes, axis: .vertical).lineLimit(3...8)
                }
            }
            .tint(color)
            .navigationTitle(draft.isNew ? "Create habit" : "Edit habit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).bold().disabled(!isValid) }
            }
            .sheet(isPresented: $pickingColor) {
                ColorGrid(selected: colorIndex) { colorIndex = $0; pickingColor = false }
                    .padding()
                    .presentationDetents([.height(320)])
            }
        }
    }

    // Android's FrequencyPickerDialog
    private var frequencySection: some View {
        Section("Frequency") {
            Picker("Repeat", selection: $frequency) {
                ForEach(FrequencyChoice.yesNo, id: \.self) { Text($0.title).tag($0) }
            }
            switch frequency {
            case .everyXDays:
                Stepper("Every \(y) days", value: $y, in: 2...365)
            case .xPerWeek:
                Stepper("\(x) times per week", value: $x, in: 1...6)
            case .xPerMonth:
                Stepper("\(x) times per month", value: $x, in: 1...29)
            case .xInYDays:
                Stepper("\(x) times", value: $x, in: 1...max(1, y - 1))
                Stepper("in \(y) days", value: $y, in: 2...365)
            default:
                EmptyView()
            }
        }
    }

    // EditHabitActivity's measurable fields: frequency (day/week/month), target type, target value, unit
    private var measurableSection: some View {
        Section("Target") {
            Picker("Frequency", selection: $frequency) {
                ForEach(FrequencyChoice.measurable, id: \.self) { Text($0.title).tag($0) }
            }
            Picker("Target type", selection: $targetAtMost) {
                Text("At least").tag(false)
                Text("At most").tag(true)
            }
            HStack {
                Text("Target")
                TextField("e.g. 15", text: $target).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    .accessibilityIdentifier("edit.target")
            }
            HStack {
                Text("Unit")
                TextField("e.g. miles", text: $unit).multilineTextAlignment(.trailing)
                    .accessibilityIdentifier("edit.unit")
            }
        }
    }

    private func save() {
        let (num, den) = frequency.fraction(x: x, y: y)
        draft.name = name
        draft.question = question
        draft.notes = notes
        draft.category = category
        draft.colorIndex = Int32(colorIndex)
        draft.freqNum = Int32(num)
        draft.freqDen = Int32(den)
        if draft.isNumerical {
            draft.targetValue = targetValue ?? 0
            draft.targetAtMost = targetAtMost
            draft.unit = unit
        }
        // ponytail: only stored on the habit; notification scheduling lives elsewhere
        draft.hasReminder = hasReminder
        let c = Calendar.current.dateComponents([.hour, .minute], from: reminderTime)
        draft.reminderHour = Int32(c.hour ?? 8)
        draft.reminderMinute = Int32(c.minute ?? 0)
        draft.reminderDays = Int32(reminderDays)
        draft.save()
        dismiss()
    }
}

enum FrequencyChoice: Hashable {
    case everyDay, everyWeek, everyMonth, everyXDays, xPerWeek, xPerMonth, xInYDays

    static let yesNo: [FrequencyChoice] = [.everyDay, .everyXDays, .xPerWeek, .xPerMonth, .xInYDays]
    static let measurable: [FrequencyChoice] = [.everyDay, .everyWeek, .everyMonth]

    var title: String {
        switch self {
        case .everyDay: "Every day"
        case .everyWeek: "Every week"
        case .everyMonth: "Every month"
        case .everyXDays: "Every X days"
        case .xPerWeek: "X times per week"
        case .xPerMonth: "X times per month"
        case .xInYDays: "X times in Y days"
        }
    }

    /// Same cases as Android's FrequencyPickerDialog.populate. Returns the choice plus its X and Y.
    static func from(num: Int, den: Int) -> (FrequencyChoice, Int, Int) {
        if den == 30 || den == 31 { return (.xPerMonth, num, 30) }
        if num == 1 && den == 1 { return (.everyDay, 1, 2) }
        if num == 1 { return (.everyXDays, 1, den) }
        if den == 7 { return (.xPerWeek, num, 7) }
        return (.xInYDays, num, den)
    }

    static func numerical(den: Int) -> FrequencyChoice {
        den == 7 ? .everyWeek : den >= 30 ? .everyMonth : .everyDay
    }

    /// Numerator/denominator, with FrequencyPickerDialog.onSaveClicked's fallback to daily.
    func fraction(x: Int, y: Int) -> (Int, Int) {
        let (n, d): (Int, Int) = switch self {
        case .everyDay: (1, 1)
        case .everyWeek: (1, 7)
        case .everyMonth: (1, 30)
        case .everyXDays: (1, y)
        case .xPerWeek: (x, 7)
        case .xPerMonth: (x, 30)
        case .xInYDays: (x, y)
        }
        return n >= d || n < 1 ? (1, 1) : (n, d)
    }
}

/// Reminder days as a packed WeekdayList (bit 0 = Saturday, bit 1 = Sunday, ..., bit 6 = Friday),
/// shown in the locale's week order.
struct WeekdayPicker: View {
    @Binding var packed: Int
    let tint: Color

    var body: some View {
        let cal = Calendar.current
        let order = (0..<7).map { (cal.firstWeekday - 1 + $0) % 7 } // 0 = Sunday, as in weekdaySymbols
        HStack {
            ForEach(order, id: \.self) { weekday in
                let bit = 1 << ((weekday + 1) % 7)
                let on = packed & bit != 0
                Button {
                    let next = packed ^ bit
                    packed = next == 0 ? 127 : next // empty means every day, as on Android
                } label: {
                    Text(cal.veryShortWeekdaySymbols[weekday])
                        .font(.subheadline.bold())
                        .frame(width: 34, height: 34)
                        .foregroundStyle(on ? .white : .secondary)
                        .background(on ? tint : Color(.systemGray5), in: Circle())
                }
                .buttonStyle(.borderless)
                .frame(maxWidth: .infinity)
                .accessibilityLabel(cal.weekdaySymbols[weekday])
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}
