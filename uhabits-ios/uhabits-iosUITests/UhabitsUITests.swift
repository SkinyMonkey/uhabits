import XCTest

/// Drives the real app with taps and typing. Every test starts from a fresh install (`-resetData`)
/// seeded by `-seed` (Meditate, Exercise, numeric Run) or `-seedFull` (Meditate, Exercise, Run, Read, archived Old habit).
final class UhabitsUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(_ args: String...) {
        app = XCUIApplication()
        app.launchArguments = ["-resetData"] + args
        app.launch()
    }

    private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private func labelled(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func waitFor(_ e: XCUIElement, _ timeout: TimeInterval = 5, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "missing \(e)", file: file, line: line)
    }

    private func waitLabel(_ e: XCUIElement, contains text: String, file: StaticString = #filePath, line: UInt = #line) {
        let p = NSPredicate(format: "label CONTAINS %@", text)
        let r = XCTWaiter().wait(for: [expectation(for: p, evaluatedWith: e)], timeout: 5)
        XCTAssertEqual(r, .completed, "label of \(e) is '\(e.label)', expected to contain '\(text)'", file: file, line: line)
    }

    private func waitGone(_ e: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let r = XCTWaiter().wait(for: [expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: e)], timeout: 5)
        XCTAssertEqual(r, .completed, "\(e) still exists", file: file, line: line)
    }

    private func clearAndType(_ field: XCUIElement, _ text: String) {
        field.tap()
        let current = (field.value as? String) ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count + 2) + text)
    }

    private func flipSwitch(_ id: String) {
        // The switch sits at the trailing edge; tapping the label of a Form toggle does nothing
        element(id).coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
    }

    // MARK: List

    func testTapYesNoDayOpensEntrySheetAndLongPressToggles() {
        launch("-seed")
        let day = element("day.Exercise.0")
        waitFor(day)
        waitLabel(day, contains: "unknown")

        // Short-toggle is off by default: a tap opens the entry sheet
        day.tap()
        waitFor(element("entry.Yes"))
        element("entry.Yes").tap()
        waitGone(element("entry.Yes"))
        waitLabel(day, contains: "done")

        // A long press toggles without a sheet: YES -> NO (wait for the sheet's dismiss animation to settle)
        let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: day)
        XCTAssertEqual(XCTWaiter().wait(for: [hittable], timeout: 5), .completed)
        day.press(forDuration: 1)
        waitLabel(day, contains: "not done")
        XCTAssertFalse(element("entry.Yes").exists)
    }

    func testShortToggleSettingMakesTapToggle() {
        launch("-seed")
        app.buttons["Settings"].tap()
        waitFor(element("settings.shortToggle"))
        flipSwitch("settings.shortToggle")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let day = element("day.Exercise.0")
        waitFor(day)
        day.tap()
        waitLabel(day, contains: "done")
        XCTAssertFalse(element("entry.Yes").exists)
        // ... and the long press opens the sheet
        day.press(forDuration: 1)
        waitFor(element("entry.Yes"))
        app.navigationBars.buttons["Cancel"].tap()
        waitGone(element("entry.Yes"))

        // Same on the history chart: a tap toggles (Total 1 -> 2), a long press opens the sheet
        element("habit.Exercise").tap()
        let total = element("overview.Total")
        waitFor(total)
        XCTAssertEqual(total.label, "1")
        let chart = element("historyChart")
        for _ in 0..<5 where !chart.isHittable { app.swipeUp() }
        let oldDay = chart.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.2))
        oldDay.tap()
        XCTAssertFalse(element("entry.Yes").waitForExistence(timeout: 1))
        oldDay.press(forDuration: 1)
        waitFor(element("entry.Yes"))
        app.navigationBars.buttons["Cancel"].tap()
        for _ in 0..<5 where !total.isHittable { app.swipeDown() }
        waitLabel(total, contains: "2")
    }

    func testEnterNumericValue() {
        launch("-seed")
        let day = element("day.Run.0")
        waitFor(day)
        day.tap()
        let field = element("entry.value")
        waitFor(field)
        clearAndType(field, "7.5")
        app.navigationBars.buttons["Save"].tap()
        waitGone(field)
        waitLabel(day, contains: "7.5 km")
    }

    func testCreateYesNoAndMeasurableHabits() {
        launch("-seed")
        waitFor(element("habit.Meditate"))

        app.buttons["New habit"].tap()
        app.buttons["Yes or No"].tap()
        let name = element("edit.name")
        waitFor(name)
        name.tap()
        name.typeText("Floss")
        app.navigationBars.buttons["Save"].tap()
        waitFor(element("habit.Floss"))

        app.buttons["New habit"].tap()
        app.buttons["Measurable"].tap()
        waitFor(name)
        name.tap()
        name.typeText("Pages")
        XCTAssertFalse(app.navigationBars.buttons["Save"].isEnabled, "a measurable habit needs a target")
        element("edit.target").tap()
        element("edit.target").typeText("20")
        element("edit.unit").tap()
        element("edit.unit").typeText("pages")
        app.navigationBars.buttons["Save"].tap()
        waitFor(element("habit.Pages"))
        waitLabel(element("day.Pages.0"), contains: "no value") // a numeric button, empty today
    }

    func testEditHabitNameAndFrequency() {
        launch("-seed")
        let row = element("habit.Exercise")
        waitFor(row)
        row.press(forDuration: 1)
        app.buttons["Edit"].tap()
        let name = element("edit.name")
        waitFor(name)
        clearAndType(name, "Workout")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Repeat'")).firstMatch.tap()
        app.buttons["Every X days"].tap()
        // Y starts at the old denominator (3 times per week -> every 7 days); one step up makes it 8
        let increment = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Every 7 days'")).matching(identifier: "Increment").firstMatch
        waitFor(increment)
        increment.tap()
        waitFor(labelled("Every 8 days"))
        app.navigationBars.buttons["Save"].tap()

        let renamed = element("habit.Workout")
        waitFor(renamed)
        XCTAssertFalse(element("habit.Exercise").exists)
        renamed.tap()
        waitFor(labelled("Every 8 days"))
    }

    func testArchiveThenDeleteHabit() {
        launch("-seed")
        let row = element("habit.Exercise")
        waitFor(row)
        row.press(forDuration: 1)
        app.buttons["Archive"].tap()
        waitGone(row)

        // Show archived habits again, then delete it
        app.buttons["Options"].tap()
        app.buttons["Hide archived"].tap()
        waitFor(row)
        row.press(forDuration: 1)
        app.buttons["Delete"].tap()
        waitFor(app.alerts.firstMatch)
        app.alerts.buttons["Delete"].tap()
        waitGone(row)
        XCTAssertTrue(element("habit.Meditate").exists)
    }

    // MARK: Show habit

    func testShowHabitCardsAndHistoryEntry() {
        launch("-seedFull")
        waitFor(element("habit.Meditate"))
        element("habit.Meditate").tap()

        for title in ["Overview", "Score", "Count"] { waitFor(app.staticTexts[title]) }
        let total = element("overview.Total")
        XCTAssertEqual(total.label, "8")

        let chart = element("historyChart")
        for _ in 0..<5 where !chart.isHittable { app.swipeUp() }
        XCTAssertTrue(chart.isHittable)
        for title in ["History", "Best streaks", "Frequency"] { XCTAssertTrue(app.staticTexts[title].exists, title) }

        // The oldest column, first row: a day long before the seeded history. Short-toggle is off: a tap opens the sheet
        let oldDay = chart.coordinate(withNormalizedOffset: CGVector(dx: 0.04, dy: 0.2))
        oldDay.tap()
        waitFor(element("entry.Yes"))
        element("entry.notes").tap()
        element("entry.notes").typeText("Long ago")
        element("entry.Yes").tap()
        waitGone(element("entry.Yes"))

        // Notes were saved with the value: the sheet shows them again
        oldDay.tap()
        waitFor(element("entry.notes"))
        XCTAssertEqual(element("entry.notes").value as? String, "Long ago")
        app.navigationBars.buttons["Cancel"].tap()
        waitGone(element("entry.notes"))

        for _ in 0..<5 where !total.isHittable { app.swipeDown() }
        waitLabel(total, contains: "9")
    }

    // MARK: Settings

    func testReverseOrderOfDaysSetting() {
        launch("-seed")
        let today = element("day.Meditate.0"), yesterday = element("day.Meditate.1")
        waitFor(today)
        XCTAssertLessThan(today.frame.minX, yesterday.frame.minX)

        app.buttons["Settings"].tap()
        waitFor(element("settings.reverse"))
        flipSwitch("settings.reverse")
        XCTAssertEqual(element("settings.reverse").value as? String, "1")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        waitFor(today)
        XCTAssertGreaterThan(today.frame.minX, yesterday.frame.minX)
    }

    func testExportCSVShowsShareSheet() {
        launch("-seed")
        app.buttons["Settings"].tap()
        let export = element("settings.exportCSV")
        waitFor(app.navigationBars["Settings"])
        for _ in 0..<5 where !export.isHittable { app.swipeUp() }
        export.tap()
        waitFor(app.otherElements["ActivityListView"], 10)
    }
}
