import SwiftUI
import Charts
import class UhabitsCore.IosShowHabit
import class UhabitsCore.ShowHabitSnapshot

// Cards of the show-habit screen, mirroring uhabits-android .../activities/habits/show/views.
// History and bar charts are the shared core charts drawn by IosCanvas; the others (Android-only
// views there) are drawn natively from the presenter state.

struct ShowHabitCard<Content: View>: View {
    let s: ShowHabitSnapshot
    var title: String?
    var trailing: AnyView?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                HStack {
                    Text(title).font(.headline).foregroundStyle(Color(hex: s.color))
                    Spacer()
                    trailing
                }
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(hex: s.cardBackground), in: RoundedRectangle(cornerRadius: 8))
    }
}

struct SubtitleCard: View {
    let s: ShowHabitSnapshot

    var body: some View {
        ShowHabitCard(s: s) {
            if !s.question.isEmpty {
                Text(s.question).foregroundStyle(Color(hex: s.color))
            }
            HStack(spacing: 16) {
                Label(s.frequencyText, systemImage: "calendar")
                if !s.targetText.isEmpty { Label(s.targetText, systemImage: "target") }
                Label(s.reminderText, systemImage: "bell")
                if !s.category.isEmpty { Label(s.category, systemImage: "tag") }
            }
            .font(.footnote)
            .foregroundStyle(Color(hex: s.mediumTextColor))
        }
    }
}

struct NotesCard: View {
    let s: ShowHabitSnapshot

    var body: some View {
        ShowHabitCard(s: s) { Text(s.notes).foregroundStyle(Color(hex: s.textColor)) }
    }
}

struct OverviewCard: View {
    let s: ShowHabitSnapshot

    private func diff(_ d: Double) -> String { String(format: "%@%.0f%%", d >= 0 ? "+" : "\u{2212}", abs(d) * 100) }

    var body: some View {
        let color = Color(hex: s.color)
        let inactive = Color(hex: s.mediumTextColor)
        ShowHabitCard(s: s, title: "Overview") {
            HStack {
                ZStack {
                    Circle().stroke(Color(hex: s.lowContrastColor), lineWidth: 6)
                    Circle().trim(from: 0, to: s.scoreToday).stroke(color, lineWidth: 6).rotationEffect(.degrees(-90))
                }
                .frame(width: 40, height: 40)
                stat(String(format: "%.0f%%", s.scoreToday * 100), "Score", color)
                stat(diff(s.scoreMonthDiff), "Month", s.scoreMonthDiff >= 0 ? color : inactive)
                stat(diff(s.scoreYearDiff), "Year", s.scoreYearDiff >= 0 ? color : inactive)
                stat("\(s.totalCount)", "Total", color)
            }
        }
    }

    private func stat(_ value: String, _ label: String, _ color: Color) -> some View {
        VStack {
            Text(value).font(.title3.bold()).foregroundStyle(color)
            Text(label).font(.caption).foregroundStyle(Color(hex: s.mediumTextColor))
        }
        .frame(maxWidth: .infinity)
    }
}

struct TargetCard: View {
    let s: ShowHabitSnapshot

    private func label(_ interval: Int32) -> String {
        switch interval {
        case 1: return "Today"
        case 7: return "Week"
        case 30: return "Month"
        case 91: return "Quarter"
        default: return "Year"
        }
    }

    var body: some View {
        ShowHabitCard(s: s, title: "Target") {
            ForEach(Array(s.targets.enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(label(row.interval))
                        Spacer()
                        Text(String(format: "%g / %g", row.value, row.target))
                    }
                    .font(.footnote)
                    .foregroundStyle(Color(hex: s.textColor))
                    ProgressView(value: row.target > 0 ? min(row.value / row.target, 1) : 1)
                        .tint(Color(hex: s.color))
                }
            }
        }
    }
}

/// Picker for a presenter spinner; the presenter saves it in preferences and triggers a refresh.
private func bucketPicker(_ labels: [String], _ selection: Int32, _ s: ShowHabitSnapshot, _ set: @escaping (Int32) -> Void) -> AnyView {
    AnyView(
        Picker("Interval", selection: Binding(get: { selection }, set: set)) {
            ForEach(labels.indices, id: \.self) { Text(labels[$0]).tag(Int32($0)) }
        }
        .pickerStyle(.menu)
        .tint(Color(hex: s.mediumTextColor))
    )
}

private let intervalLabels = ["Day", "Week", "Month", "Quarter", "Year"]

struct ScoreCard: View {
    let s: ShowHabitSnapshot
    let helper: IosShowHabit

    var body: some View {
        let color = Color(hex: s.color)
        // ponytail: last 30 buckets, no horizontal scrolling like Android's ScoreChart
        let points = s.scores.prefix(30).map { (Date(timeIntervalSince1970: $0.unixSeconds), $0.value * 100) }
        ShowHabitCard(s: s, title: "Score", trailing: bucketPicker(intervalLabels, s.scoreSpinner, s) { helper.setScoreSpinner(position: $0) }) {
            Chart(points, id: \.0) { p in
                LineMark(x: .value("Date", p.0), y: .value("Score", p.1)).foregroundStyle(color)
                PointMark(x: .value("Date", p.0), y: .value("Score", p.1)).foregroundStyle(color).symbolSize(16)
            }
            .chartYScale(domain: 0...100)
            .chartYAxis { AxisMarks(values: [0, 20, 40, 60, 80, 100]) { v in
                AxisGridLine(); AxisValueLabel { Text("\(v.as(Int.self) ?? 0)%") }
            } }
            .frame(height: 200)
        }
    }
}

/// Shows a core chart rendered to a UIImage at the view's width; `onTap` gets points in that image.
struct SharedChartImage: View {
    let height: CGFloat
    let render: (CGFloat, CGFloat, CGFloat) -> UIImage
    var onTap: ((CGPoint) -> Void)?
    @Environment(\.displayScale) private var scale

    var body: some View {
        GeometryReader { geo in
            let image = Image(uiImage: render(geo.size.width, height, scale))
                .resizable()
                .frame(width: geo.size.width, height: height)
            if let onTap {
                image.contentShape(Rectangle()).onTapGesture(coordinateSpace: .local) { onTap($0) }
            } else {
                image
            }
        }
        .frame(height: height)
    }
}

struct BarCard: View {
    let s: ShowHabitSnapshot
    let helper: IosShowHabit

    var body: some View {
        let labels = s.isNumerical ? intervalLabels : Array(intervalLabels.dropFirst())
        ShowHabitCard(s: s, title: s.isNumerical ? "Total" : "Count", trailing: bucketPicker(labels, s.barSpinner, s) { helper.setBarSpinner(position: $0) }) {
            SharedChartImage(height: 200) { w, h, scale in
                helper.renderBar(snapshot: s, width: w, height: h, scale: scale)
            }
        }
    }
}

struct HistoryCard: View {
    let s: ShowHabitSnapshot
    let helper: IosShowHabit

    var body: some View {
        ShowHabitCard(s: s, title: "History") {
            SharedChartImage(height: 180, render: { w, h, scale in
                helper.renderHistory(snapshot: s, width: w, height: h, scale: scale)
            }, onTap: { p in
                helper.tapHistory(x: p.x, y: p.y)
            })
        }
    }
}

struct StreakCard: View {
    let s: ShowHabitSnapshot

    var body: some View {
        let maxLength = max(1, s.streaks.map { Int($0.length) }.max() ?? 1)
        let text = Color(hex: s.mediumTextColor)
        ShowHabitCard(s: s, title: "Best streaks") {
            if s.streaks.isEmpty {
                Text("No streaks yet").font(.footnote).foregroundStyle(text)
            }
            ForEach(Array(s.streaks.enumerated()), id: \.offset) { i, streak in
                let fraction = Double(streak.length) / Double(maxLength)
                HStack(spacing: 6) {
                    Text(streak.start).frame(width: 90, alignment: .trailing)
                    GeometryReader { geo in
                        ZStack {
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color(hex: s.color).opacity(i == 0 ? 1 : 0.4 + 0.6 * fraction))
                                .frame(width: max(24, geo.size.width * fraction))
                            Text("\(streak.length)").foregroundStyle(Color(hex: s.cardBackground))
                        }
                        .frame(maxWidth: .infinity)
                    }
                    Text(streak.end).frame(width: 90, alignment: .leading)
                }
                .font(.caption2)
                .foregroundStyle(text)
                .frame(height: 18)
            }
        }
    }
}

struct FrequencyCard: View {
    let s: ShowHabitSnapshot

    var body: some View {
        // Oldest month on the left, like Android's FrequencyChart; ponytail: last 12 months only
        let months = Array(s.frequency.prefix(12).reversed())
        let maxCount = max(1, months.flatMap { $0.counts.map { $0.intValue } }.max() ?? 1)
        let text = Color(hex: s.mediumTextColor)
        ShowHabitCard(s: s, title: "Frequency") {
            Grid(horizontalSpacing: 2, verticalSpacing: 4) {
                ForEach(0..<7, id: \.self) { row in
                    GridRow {
                        ForEach(months.indices, id: \.self) { col in
                            let v = months[col].counts[row].intValue
                            Circle()
                                .fill(v == 0 ? Color(hex: s.lowContrastColor) : Color(hex: s.color))
                                .frame(width: 18, height: 18)
                                .scaleEffect(v == 0 ? 0.4 : 0.5 + 0.5 * Double(v) / Double(maxCount))
                        }
                        Text(s.weekdays[row]).font(.caption2).foregroundStyle(text).frame(width: 16)
                    }
                }
                GridRow {
                    ForEach(months.indices, id: \.self) { col in
                        Text(months[col].month.split(separator: " ").first.map(String.init) ?? "")
                            .font(.system(size: 8)).foregroundStyle(text).lineLimit(1).fixedSize()
                    }
                }
            }
            .frame(maxWidth: .infinity)
            if months.isEmpty { Text("No data yet").font(.footnote).foregroundStyle(text) }
        }
    }
}
