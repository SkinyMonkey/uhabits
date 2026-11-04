import Charts
import SwiftUI
import WidgetKit

// Widget content, shared by the extension and the app's `-renderWidgets` debug renderer.
// The extension adds the container background and wraps toggles in Button(intent:) (see UhabitsWidgets.swift).

/// Entry.YES_MANUAL / YES_AUTO / SKIP, as stored by the core.
private let yesManual: Int32 = 2, yesAuto: Int32 = 1, skip: Int32 = 3

/// A tap target that toggles today's checkmark of a yes/no habit. Inert outside the extension.
struct ToggleArea<Label: View>: View {
    let habit: HabitData
    @ViewBuilder let label: () -> Label

    var body: some View {
        #if WIDGET_EXTENSION
        if !habit.isNumerical {
            Button(intent: ToggleHabitIntent(habitId: Int(habit.id)), label: label).buttonStyle(.plain)
        } else {
            label()
        }
        #else
        label()
        #endif
    }
}

struct MessageView: View {
    let text: String
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
    }
}

/// Today's state as Android's checkmark widget shows it: check, cross, skip or the number.
struct CheckGlyph: View {
    let habit: HabitData
    let color: Color
    var size: CGFloat = 22

    var body: some View {
        if habit.isNumerical {
            Text(habit.numberLabel).font(.system(size: size * 0.8, weight: .bold)).foregroundStyle(color)
        } else {
            let (name, on): (String, Bool) = switch habit.value {
            case yesManual: ("checkmark", true)
            case yesAuto: ("checkmark", false)
            case skip: ("arrow.right", false)
            default: ("xmark", false)
            }
            Image(systemName: name).font(.system(size: size, weight: .bold))
                .foregroundStyle(on ? color : Color.secondary.opacity(0.6))
        }
    }
}

/// The score ring around the checkmark, like CheckmarkWidgetView.
struct WidgetRing<Content: View>: View {
    let score: Double
    let color: Color
    var lineWidth: CGFloat = 6
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.2), lineWidth: lineWidth)
            Circle().trim(from: 0, to: max(0.001, score)).stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
            content()
        }
        .padding(lineWidth / 2)
    }
}

// MARK: - Single-habit widgets

/// Small: ring + today's state + name; the whole widget toggles.
struct CheckmarkWidgetView: View {
    let entry: HabitEntry
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let habit = entry.habit {
            let color = habit.color(scheme)
            ToggleArea(habit: habit) {
                VStack(spacing: 8) {
                    WidgetRing(score: habit.score, color: color) { CheckGlyph(habit: habit, color: color, size: 26) }
                        .frame(maxWidth: 84, maxHeight: 84)
                    Text(habit.name).font(.subheadline.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
                        .foregroundStyle(habit.isCompleted ? color : .primary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
            }
        } else {
            MessageView(text: entry.message ?? "")
        }
    }
}

struct HabitTitle: View {
    let habit: HabitData
    let color: Color
    var body: some View {
        Text(habit.name).font(.subheadline.weight(.semibold)).foregroundStyle(color).lineLimit(1)
    }
}

/// Medium / large: the shared HistoryChart image.
struct HistoryWidgetView: View {
    let entry: HabitEntry
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let habit = entry.habit {
            VStack(alignment: .leading, spacing: 4) {
                HabitTitle(habit: habit, color: habit.color(scheme))
                if let h = entry.history {
                    Image(uiImage: scheme == .dark ? h.dark : h.light).resizable().scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        } else {
            MessageView(text: entry.message ?? "")
        }
    }

    /// Chart size for a widget of [size]; matches the padding and title above.
    static func chartSize(for size: CGSize) -> CGSize {
        CGSize(width: size.width - 32, height: size.height - 32 - 24)
    }
}

/// Small: ring with the percentage. Medium: percentage plus the score line, like Android's ScoreWidget.
struct ScoreWidgetView: View {
    let entry: HabitEntry
    let family: WidgetFamily
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let habit = entry.habit {
            let color = habit.color(scheme)
            let percent = Text("\(Int((habit.score * 100).rounded()))%").font(.title3.weight(.bold)).foregroundStyle(color)
            if family == .systemSmall {
                VStack(spacing: 8) {
                    WidgetRing(score: habit.score, color: color) { percent }.frame(maxWidth: 84, maxHeight: 84)
                    Text(habit.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    HStack { HabitTitle(habit: habit, color: color); Spacer(); percent }
                    Chart(Array(habit.scores.enumerated()), id: \.offset) { i, v in
                        AreaMark(x: .value("t", i), y: .value("score", v)).foregroundStyle(color.opacity(0.15))
                        LineMark(x: .value("t", i), y: .value("score", v)).foregroundStyle(color)
                            .lineStyle(StrokeStyle(lineWidth: 2))
                    }
                    .chartYScale(domain: 0...1)
                    .chartXAxis(.hidden)
                    .chartYAxis { AxisMarks(values: [0, 0.5, 1]) { AxisGridLine() } }
                }
            }
        } else {
            MessageView(text: entry.message ?? "")
        }
    }
}

/// Best streaks as bars, like Android's StreakWidget.
struct StreakWidgetView: View {
    let entry: HabitEntry
    let family: WidgetFamily
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let habit = entry.habit {
            let color = habit.color(scheme)
            let streaks = habit.streaks.prefix(family == .systemSmall ? 4 : 5)
            let longest = max(1, streaks.map(\.length).max() ?? 1)
            VStack(alignment: .leading, spacing: 6) {
                HabitTitle(habit: habit, color: color)
                if streaks.isEmpty { MessageView(text: "No streaks yet") }
                ForEach(Array(streaks.enumerated()), id: \.offset) { _, s in
                    HStack(spacing: 6) {
                        if family != .systemSmall {
                            Text(s.start).font(.caption2).foregroundStyle(.secondary).frame(width: 48, alignment: .trailing)
                        }
                        GeometryReader { g in
                            let w = max(24, g.size.width * CGFloat(s.length) / CGFloat(longest))
                            RoundedRectangle(cornerRadius: 3).fill(color.opacity(s.length == longest ? 1 : 0.6))
                                .frame(width: w)
                                .overlay(alignment: .leading) {
                                    Text("\(s.length)").font(.caption2.weight(.bold)).foregroundStyle(.white).padding(.leading, 4)
                                }
                        }
                        .frame(height: 16)
                        if family != .systemSmall {
                            Text(s.end).font(.caption2).foregroundStyle(.secondary).frame(width: 48, alignment: .leading)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        } else {
            MessageView(text: entry.message ?? "")
        }
    }
}

// MARK: - List widget

/// Medium / large: today's checkmark for each habit, each one a toggle.
struct HabitListWidgetView: View {
    let entry: HabitEntry
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if entry.habits.isEmpty {
            MessageView(text: entry.message ?? "")
        } else {
            VStack(spacing: 0) {
                ForEach(entry.habits) { habit in
                    let color = habit.color(scheme)
                    HStack(spacing: 10) {
                        WidgetRing(score: habit.score, color: color, lineWidth: 3) { EmptyView() }.frame(width: 16, height: 16)
                        Text(habit.name).font(.subheadline.weight(.medium)).lineLimit(1)
                            .foregroundStyle(habit.isCompleted ? color : .primary)
                        Spacer()
                        ToggleArea(habit: habit) {
                            CheckGlyph(habit: habit, color: color, size: 18).frame(width: 44, height: 28).contentShape(Rectangle())
                        }
                    }
                    .frame(maxHeight: .infinity)
                }
            }
        }
    }

    static func limit(for family: WidgetFamily) -> Int { family == .systemLarge ? 8 : 3 }
}
