import SwiftUI
import UserNotifications
// Import only what we use: the core also exports View, Color, etc., which clash with SwiftUI
import class UhabitsCore.HabitItem
import class UhabitsCore.IosAppGraph
import class UhabitsCore.KotlinBooleanArray
import class UhabitsCore.IosImportExport
import class UhabitsCore.IosReminders
import class UhabitsCore.PaletteColor
import class UhabitsCore.Reminder
import class UhabitsCore.WeekdayList
import protocol UhabitsCore.Command
import protocol UhabitsCore.CommandRunnerListener

@main
struct UhabitsApp: App {
    @StateObject private var store = HabitStore()
    @AppStorage("pref_theme") private var theme = 0 // ThemeSwitcher: 0 automatic, 1 dark, 2 light

    var body: some Scene {
        WindowGroup {
            Group {
                // Debug: `-showHabit <id>` opens that habit, `-openSettings` the settings (screenshots without tapping)
                if let id = Int64(UserDefaults.standard.string(forKey: "showHabit") ?? "") {
                    NavigationStack { ShowHabitView(habitId: id) }
                } else if CommandLine.arguments.contains("-openSettings") {
                    NavigationStack { SettingsView() }
                } else {
                    HabitListView()
                }
            }
            .environmentObject(store)
            .preferredColorScheme(theme == 1 ? .dark : theme == 2 ? .light : nil)
        }
    }
}

/// Thin wrapper over the Kotlin graph. All calls are synchronous; the list is re-read after each change.
final class HabitStore: ObservableObject {
    let graph: IosAppGraph
    let reminders: IosReminders
    let importExport: IosImportExport
    private let notificationDelegate: NotificationDelegate
    private let refresher = CommandRefresher()
    @Published private(set) var habits: [HabitItem] = []

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        // uhabits-core/assets/main is bundled as the folder reference "main" (migrations/, fonts/)
        graph = IosAppGraph(resourceRoot: Bundle.main.bundlePath + "/main", userRoot: docs)
        reminders = IosReminders(graph: graph)
        importExport = IosImportExport(graph: graph)
        notificationDelegate = NotificationDelegate(reminders: reminders)
        UNUserNotificationCenter.current().delegate = notificationDelegate
        // Debug: `-seed` creates two habits and checks the first one, only when the DB is empty
        if CommandLine.arguments.contains("-seed"), graph.habits().isEmpty {
            graph.createHabit(name: "Meditate", colorIndex: 8)
            graph.createHabit(name: "Exercise", colorIndex: 0)
            graph.toggleToday(habitId: graph.habits()[0].id)
            graph.seedHistory(habitId: graph.habits()[0].id) // 120 days of history + a numerical "Run" habit
        }
        // Debug: `-seedReminders` gives the first habit a 09:00 reminder on Mon/Wed/Fri
        if CommandLine.arguments.contains("-seedReminders"), let first = graph.habits().first,
           let habit = graph.habitList.getById(id: first.id) {
            // WeekdayList index 0 = Saturday, 1 = Sunday, 2 = Monday ... 6 = Friday
            let days = KotlinBooleanArray(size: 7)
            for i in [2, 4, 6] { days.set(index: Int32(i), value: true) }
            habit.reminder = Reminder(hour: 9, minute: 0, days: WeekdayList(weekdays: days))
            graph.habitList.update(habit: habit)
        }
        #if DEBUG
        // Debug: `-provisionalNotifications` gets quiet authorization without a prompt (simctl cannot grant it)
        if CommandLine.arguments.contains("-provisionalNotifications") {
            UNUserNotificationCenter.current().requestAuthorization(options: [.provisional]) { [reminders] _, _ in
                DispatchQueue.main.async { reminders.scheduleAll() }
            }
        }
        #endif
        reminders.scheduleAll()
        refresher.onChange = { [weak self] in self?.reload() }
        graph.commandRunner.addListener(l: refresher)
        reload()
        print("[uhabits] db=\(docs) habits=\(habits.map { "\($0.name):\($0.checkedToday)" })")
        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [reminders] in reminders.logPending() }
        #endif
    }

    func reload() { habits = graph.habits() }
    func toggle(_ habit: HabitItem) { graph.toggleToday(habitId: habit.id); reload() }
    func create(name: String, colorIndex: Int) { graph.createHabit(name: name, colorIndex: Int32(colorIndex)); reload() }
}

/// Reloads the list after commands run through the command runner (notification actions, imports).
private final class CommandRefresher: NSObject, CommandRunnerListener {
    var onChange: () -> Void = {}
    func onCommandFinished(command: any Command) { onChange() }
}

/// Shows reminders while the app is open and forwards the Check / Later actions to Kotlin.
private final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    let reminders: IosReminders
    init(reminders: IosReminders) { self.reminders = reminders }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions { [.banner, .sound] }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let id = response.notification.request.content.userInfo[IosReminders.companion.HABIT_ID_KEY] as? NSNumber
        else { return }
        await MainActor.run { reminders.handleAction(actionIdentifier: response.actionIdentifier, habitId: id.int64Value) }
    }
}
