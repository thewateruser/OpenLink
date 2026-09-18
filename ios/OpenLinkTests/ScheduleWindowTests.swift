//
//  ScheduleWindowTests.swift
//  OpenLink (parent app)
//
//  The downtime allow-list crosses the wire to a completely separate Kotlin
//  implementation, so the shape of `exemptPackages` is a contract, not an
//  internal detail. The pairing QR already taught this project what happens
//  when the two halves disagree about a format and nothing checks it.
//
//  The compatibility cases matter most here. A window saved before this
//  feature existed has no `exemptPackages` at all, and a child device running
//  an older build will never send one — both must read as "this window blocks
//  everything", which is exactly what those windows did.
//

import XCTest

final class ScheduleWindowTests: XCTestCase {

    private let phone = "com.android.dialer"
    private let messages = "com.google.android.apps.messaging"

    private func decode(_ json: String) throws -> ScheduleWindow {
        try JSONCoding.decoder.decode(ScheduleWindow.self, from: Data(json.utf8))
    }

    private func encodeToObject(_ window: ScheduleWindow) throws -> [String: Any] {
        let data = try JSONCoding.encoder.encode(window)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    // MARK: - Compatibility

    /// A window from before the feature, or from an older child build.
    func testAMissingExemptListDecodesAsNoExemptions() throws {
        let window = try decode("""
        {"id":1,"daysOfWeek":127,"startMinute":1320,"endMinute":420,"label":"Bedtime"}
        """)
        XCTAssertEqual(window.exemptPackages, [])
        XCTAssertEqual(window.remoteId, "1")
        XCTAssertEqual(window.startMinute, 1320)
    }

    func testAnEmptyExemptListIsAlsoNoExemptions() throws {
        let window = try decode("""
        {"daysOfWeek":127,"startMinute":1320,"endMinute":420,"exemptPackages":[]}
        """)
        XCTAssertEqual(window.exemptPackages, [])
    }

    // MARK: - The field itself

    func testExemptPackagesDecodeInOrder() throws {
        let window = try decode("""
        {"daysOfWeek":127,"startMinute":1320,"endMinute":420,
         "exemptPackages":["\(phone)","\(messages)"]}
        """)
        XCTAssertEqual(window.exemptPackages, [phone, messages])
    }

    func testExemptPackagesSurviveARoundTrip() throws {
        let original = ScheduleWindow(
            remoteId: "7",
            daysOfWeek: 0b0111110,
            startMinute: 22 * 60,
            endMinute: 7 * 60,
            label: "Bedtime",
            exemptPackages: [phone, messages]
        )
        let data = try JSONCoding.encoder.encode(original)
        let decoded = try JSONCoding.decoder.decode(ScheduleWindow.self, from: data)

        XCTAssertEqual(decoded.exemptPackages, [phone, messages])
        XCTAssertEqual(decoded.remoteId, "7")
        XCTAssertEqual(decoded.daysOfWeek, 0b0111110)
        XCTAssertEqual(decoded.label, "Bedtime")
    }

    /// `PUT /schedule` replaces the whole set, so an omitted list would still
    /// clear the exemptions — but the child's Kotlin DTO defaults the field,
    /// and sending it explicitly is what makes "I removed them all"
    /// unambiguous on the wire rather than a coincidence of two defaults
    /// happening to agree.
    func testAnEmptyExemptListIsStillSent() throws {
        let object = try encodeToObject(
            ScheduleWindow(daysOfWeek: 127, startMinute: 0, endMinute: 60))
        let encoded = try XCTUnwrap(object["exemptPackages"] as? [String])
        XCTAssertEqual(encoded, [])
    }

    func testTheIdStaysANumberOnTheWire() throws {
        // The child assigns Room row ids and sends JSON numbers; a string
        // would be a new shape for its deserialiser to cope with.
        let object = try encodeToObject(
            ScheduleWindow(remoteId: "42", daysOfWeek: 1, startMinute: 0, endMinute: 1,
                           exemptPackages: [phone]))
        XCTAssertTrue(object["id"] is NSNumber, "id encoded as \(type(of: object["id"]))")
    }

    // MARK: - Day bitmask, which the exemption UI also reads

    func testDayHelpersMatchTheBitmask() {
        var window = ScheduleWindow(daysOfWeek: 0, startMinute: 0, endMinute: 0)
        XCTAssertFalse(window.includesDay(0))

        window.setDay(0, included: true)   // Sunday is bit 0
        window.setDay(6, included: true)   // Saturday is bit 6
        XCTAssertEqual(window.daysOfWeek, 0b1000001)
        XCTAssertTrue(window.includesDay(0))
        XCTAssertTrue(window.includesDay(6))
        XCTAssertFalse(window.includesDay(3))

        window.setDay(0, included: false)
        XCTAssertEqual(window.daysOfWeek, 0b1000000)
    }
}
