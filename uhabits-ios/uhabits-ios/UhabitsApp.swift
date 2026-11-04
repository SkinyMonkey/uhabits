import SwiftUI
import UserNotifications
// Import only what we use: the core also exports View, Color, etc., which clash with SwiftUI
import class UhabitsCore.IosAppGraph
import class UhabitsCore.KotlinBooleanArray
import class UhabitsCore.IosImportExport
import class UhabitsCore.IosReminders
import class UhabitsCore.Reminder
import class UhabitsCore.WeekdayList

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
            .environmentObject(store.list)
            .preferredColorScheme(theme == 1 ? .dark : theme == 2 ? .light : nil)
        }
    }
}

/// Owns the Kotlin graph. Screens observe their own models built on it (e.g. `list`).
final class HabitStore: ObservableObject {
    let graph: IosAppGraph
    let reminders: IosReminders
    let importExport: IosImportExport
    private let notificationDelegate: NotificationDelegate
    let list: ListHabitsModel

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].path
        // uhabits-core/assets/main is bundled as the folder reference "main" (migrations/, fonts/)
        graph = IosAppGraph(resourceRoot: Bundle.main.bundlePath + "/main", userRoot: docs)
        list = ListHabitsModel(graph: graph)
        reminders = IosReminders(graph: graph)
        importExport = IosImportExport(graph: graph)
        notificationDelegate = NotificationDelegate(reminders: reminders)
        UNUserNotificationCenter.current().delegate = notificationDelegate
        // Debug, only when the DB is empty: `-seed` creates two habits plus 120 days of history and a numeric
        // "Run" habit; `-seedFull` instead adds a numeric, categorised and archived habit plus some history
        let args = CommandLine.arguments
        if (args.contains("-seed") || args.contains("-seedFull")), graph.habitList.size() == 0 {
            list.core.seed(full: args.contains("-seedFull"))
            if !args.contains("-seedFull"), let first = graph.habitList.getByPosition(position: 0).id {
                graph.seedHistory(habitId: first.int64Value)
            }
        }
        // Debug: `-seedReminders` gives the first habit a 09:00 reminder on Mon/Wed/Fri
        if args.contains("-seedReminders"), graph.habitList.size() > 0 {
            let habit = graph.habitList.getByPosition(position: 0)
            // WeekdayList index 0 = Saturday, 1 = Sunday, 2 = Monday ... 6 = Friday
            let days = KotlinBooleanArray(size: 7)
            for i in [2, 4, 6] { days.set(index: Int32(i), value: true) }
            habit.reminder = Reminder(hour: 9, minute: 0, days: WeekdayList(weekdays: days))
            graph.habitList.update(habit: habit)
        }
        #if DEBUG
        // Debug: `-provisionalNotifications` gets quiet authorization without a prompt (simctl cannot grant it)
        if args.contains("-provisionalNotifications") {
            UNUserNotificationCenter.current().requestAuthorization(options: [.provisional]) { [reminders] _, _ in
                DispatchQueue.main.async { reminders.scheduleAll() }
            }
        }
        #endif
        reminders.scheduleAll()
        reload()
        print("[uhabits] db=\(docs) rows=\(list.snapshot?.rows.count ?? 0)")
        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [reminders] in reminders.logPending() }
        #endif
    }

    /// Re-reads the list, e.g. after a setting that changes "today" or the week layout.
    func reload() { list.core.refresh() }
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
