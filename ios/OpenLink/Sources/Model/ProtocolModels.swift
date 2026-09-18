//
//  ProtocolModels.swift
//  OpenLink (parent app)
//
//  Codable types for the REST/WebSocket contract in docs/PROTOCOL.md, which
//  is served by the *child Android device itself* — there is no server.
//  Field names mirror the JSON exactly so no CodingKeys remapping is needed
//  unless noted.
//
//  Decoding is deliberately lenient on fields the protocol documents but
//  doesn't pin down (e.g. `batteryLevel`'s units, the nested `policy` object
//  in `GET /apps`): a missing optional never fails a whole response.
//

import Foundation

// MARK: - Shared JSON coding

/// Centralized JSONDecoder/JSONEncoder for the ISO-8601 UTC timestamps used
/// throughout PROTOCOL.md. It doesn't say whether fractional seconds are
/// included, so we accept both on decode and emit fractional seconds on
/// encode (a superset Ktor/kotlinx-serialization parses happily).
enum JSONCoding {
    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = ISO8601Formatters.withFractionalSeconds.date(from: string) {
                return date
            }
            if let date = ISO8601Formatters.standard.date(from: string) {
                return date
            }
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unrecognized ISO-8601 date string: \(string)"
            )
        }
        return decoder
    }()

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(ISO8601Formatters.withFractionalSeconds.string(from: date))
        }
        return encoder
    }()
}

private enum ISO8601Formatters {
    static let standard: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static let withFractionalSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

// MARK: - Pairing (POST /pair)

/// Body of `POST /pair`, the only unauthenticated route.
///
/// `parentId` is base64url of 32 random bytes; `proof` is
/// base64url(HMAC-SHA256(key: psk, msg: "openlink-pair-v1" || parentId)).
/// See `PairingService` for the exact byte-level interpretation.
struct PairRequestBody: Encodable {
    let parentId: String
    let parentName: String
    let proof: String
}

struct PairResponse: Decodable {
    let deviceId: String
    let deviceName: String
    let parentToken: String
    let endpoints: [String]

    private enum CodingKeys: String, CodingKey {
        case deviceId, deviceName, parentToken, endpoints
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        deviceName = try container.decode(String.self, forKey: .deviceName)
        parentToken = try container.decode(String.self, forKey: .parentToken)
        endpoints = try container.decodeIfPresent([String].self, forKey: .endpoints) ?? []
    }
}

/// Body of `DELETE /pair`. Omit `parentId` to unpair ourselves; pass another
/// parent's id to revoke it.
struct UnpairRequestBody: Encodable {
    let parentId: String?
}

// MARK: - Device (GET /device)

/// `GET /device` response. `endpoints` is what drives endpoint learning:
/// every successful fetch is merged into the device's stored endpoint list
/// (see `DeviceSession.applyLearnedEndpoints`).
struct DeviceInfo: Decodable, Equatable {
    let deviceId: String
    let deviceName: String
    let platform: String?
    let appVersion: String?
    let endpoints: [String]
    /// PROTOCOL.md doesn't give units; treated as a percentage 0...100 when
    /// it looks like one, and simply displayed otherwise.
    let batteryLevel: Int?
    let lastBootAt: Date?

    private enum CodingKeys: String, CodingKey {
        case deviceId, deviceName, platform, appVersion, endpoints, batteryLevel, lastBootAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        deviceName = try container.decode(String.self, forKey: .deviceName)
        platform = try container.decodeIfPresent(String.self, forKey: .platform)
        appVersion = try container.decodeIfPresent(String.self, forKey: .appVersion)
        endpoints = try container.decodeIfPresent([String].self, forKey: .endpoints) ?? []
        batteryLevel = try container.decodeIfPresent(Int.self, forKey: .batteryLevel)
        lastBootAt = try container.decodeIfPresent(Date.self, forKey: .lastBootAt)
    }
}

// MARK: - Apps and policies

/// The per-app policy the child stores. PROTOCOL.md names a nested `policy`
/// object in `GET /apps` and says `PUT /policies/{packageName}` returns "the
/// updated policy", but doesn't spell out its fields; we assume it mirrors
/// the PUT body (`dailyLimitMinutes`, `blocked`) and decode everything
/// leniently so an extra/absent key is never fatal.
struct AppPolicy: Decodable, Equatable {
    var dailyLimitMinutes: Int?
    var blocked: Bool

    private enum CodingKeys: String, CodingKey {
        case dailyLimitMinutes, blocked
    }

    init(dailyLimitMinutes: Int? = nil, blocked: Bool = false) {
        self.dailyLimitMinutes = dailyLimitMinutes
        self.blocked = blocked
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        dailyLimitMinutes = try container.decodeIfPresent(Int.self, forKey: .dailyLimitMinutes)
        blocked = try container.decodeIfPresent(Bool.self, forKey: .blocked) ?? false
    }
}

/// One row of `GET /apps`. The child enumerates its own installed launchable
/// apps, so there is no catalogue-sync step.
struct AppEntry: Decodable, Identifiable, Equatable {
    let packageName: String
    let appName: String
    let isSystemApp: Bool
    let policy: AppPolicy?
    let todayMinutes: Int

    var id: String { packageName }

    var dailyLimitMinutes: Int? { policy?.dailyLimitMinutes }
    var isBlocked: Bool { policy?.blocked ?? false }

    private enum CodingKeys: String, CodingKey {
        case packageName, appName, isSystemApp, policy, todayMinutes
    }

    init(packageName: String, appName: String, isSystemApp: Bool = false, policy: AppPolicy? = nil, todayMinutes: Int = 0) {
        self.packageName = packageName
        self.appName = appName
        self.isSystemApp = isSystemApp
        self.policy = policy
        self.todayMinutes = todayMinutes
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        packageName = try container.decode(String.self, forKey: .packageName)
        appName = try container.decodeIfPresent(String.self, forKey: .appName) ?? packageName
        isSystemApp = try container.decodeIfPresent(Bool.self, forKey: .isSystemApp) ?? false
        policy = try container.decodeIfPresent(AppPolicy.self, forKey: .policy)
        todayMinutes = try container.decodeIfPresent(Int.self, forKey: .todayMinutes) ?? 0
    }

    /// Returns a copy with a new policy applied, so the UI can update a row
    /// in place after `PUT /policies/{packageName}` without a full re-fetch.
    func applying(_ newPolicy: AppPolicy) -> AppEntry {
        AppEntry(
            packageName: packageName,
            appName: appName,
            isSystemApp: isSystemApp,
            policy: newPolicy,
            todayMinutes: todayMinutes
        )
    }
}

/// `PUT /policies/{packageName}` body.
///
/// `dailyLimitMinutes` is `number | null | absent`, and those three states are
/// semantically distinct ("set to N", "clear to unlimited", "leave alone").
/// Swift's synthesized Encodable can't express an explicit JSON null, so this
/// type encodes itself.
struct PolicyUpdateRequest: Encodable {
    enum LimitUpdate {
        case unchanged
        case set(Int)
        case clear
    }

    var dailyLimitMinutes: LimitUpdate = .unchanged
    var blocked: Bool?

    private enum CodingKeys: String, CodingKey {
        case dailyLimitMinutes, blocked
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch dailyLimitMinutes {
        case .unchanged:
            break
        case .set(let minutes):
            try container.encode(minutes, forKey: .dailyLimitMinutes)
        case .clear:
            try container.encodeNil(forKey: .dailyLimitMinutes)
        }
        if let blocked {
            try container.encode(blocked, forKey: .blocked)
        }
    }
}

// MARK: - Schedule

/// `daysOfWeek` is a 0-127 bitmask, bit0 = Sunday ... bit6 = Saturday.
/// `startMinute`/`endMinute` are minute-of-day in the *child device's* local
/// time, not the parent phone's.
struct ScheduleWindow: Codable, Identifiable, Equatable {
    /// Child-assigned id, present once a window has been saved at least once.
    var remoteId: String?
    /// Client-only identity so SwiftUI can track not-yet-saved rows too.
    private var clientId: String = UUID().uuidString
    var daysOfWeek: Int
    var startMinute: Int
    var endMinute: Int
    var label: String?
    /// Packages this window lets through — the phone, messages, an alarm.
    /// Scoped to the window, not the device, so a permissive bedtime window
    /// and a strict homework one don't leak into each other.
    var exemptPackages: [String] = []

    var id: String { remoteId ?? clientId }

    private enum CodingKeys: String, CodingKey {
        case remoteId = "id"
        case daysOfWeek, startMinute, endMinute, label, exemptPackages
    }

    init(
        remoteId: String? = nil,
        daysOfWeek: Int,
        startMinute: Int,
        endMinute: Int,
        label: String? = nil,
        exemptPackages: [String] = []
    ) {
        self.remoteId = remoteId
        self.daysOfWeek = daysOfWeek
        self.exemptPackages = exemptPackages
        self.startMinute = startMinute
        self.endMinute = endMinute
        self.label = label
    }

    /// PROTOCOL.md leaves `id`'s type open; the Android child sends its Room
    /// row id, i.e. a JSON *number*. Decoding that straight into `String?`
    /// would throw, so both forms are accepted and the id is round-tripped in
    /// whichever form it arrived.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // `try?` on a throwing call returning `Int64?` flattens to `Int64?`, so
        // `numericId` is already unwrapped here.
        if let numericId = try? container.decodeIfPresent(Int64.self, forKey: .remoteId) {
            remoteId = String(numericId)
        } else {
            remoteId = try container.decodeIfPresent(String.self, forKey: .remoteId)
        }
        daysOfWeek = try container.decode(Int.self, forKey: .daysOfWeek)
        startMinute = try container.decode(Int.self, forKey: .startMinute)
        endMinute = try container.decode(Int.self, forKey: .endMinute)
        label = try container.decodeIfPresent(String.self, forKey: .label)
        // Absent on a child device that predates the feature, and on every
        // window saved before it existed. "No exemptions" is exactly what
        // those windows meant, so this is the right default rather than a
        // lenient one.
        exemptPackages = try container.decodeIfPresent([String].self, forKey: .exemptPackages) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        if let remoteId {
            if let numericId = Int64(remoteId) {
                try container.encode(numericId, forKey: .remoteId)
            } else {
                try container.encode(remoteId, forKey: .remoteId)
            }
        }
        try container.encode(daysOfWeek, forKey: .daysOfWeek)
        try container.encode(startMinute, forKey: .startMinute)
        try container.encode(endMinute, forKey: .endMinute)
        try container.encodeIfPresent(label, forKey: .label)
        // Always encoded, including when empty: `PUT /schedule` replaces the
        // whole set, so an omitted list and an empty one mean the same thing
        // to the child — but sending it explicitly makes "I removed every
        // exemption" legible on the wire.
        try container.encode(exemptPackages, forKey: .exemptPackages)
    }

    static let dayLabels = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]

    func includesDay(_ index: Int) -> Bool {
        (daysOfWeek & (1 << index)) != 0
    }

    mutating func setDay(_ index: Int, included: Bool) {
        if included {
            daysOfWeek |= (1 << index)
        } else {
            daysOfWeek &= ~(1 << index)
        }
    }
}

/// Both `GET /schedule` and `PUT /schedule` use `{ windows }`.
struct ScheduleEnvelope: Codable {
    let windows: [ScheduleWindow]
}

// MARK: - Usage

struct UsageEntry: Decodable, Identifiable, Equatable {
    let packageName: String
    let minutesUsed: Int

    var id: String { packageName }
}

/// `GET /usage?date=YYYY-MM-DD` -> `{ date, usage: [...] }`.
struct UsageResponse: Decodable, Equatable {
    let date: String
    let usage: [UsageEntry]
}

// MARK: - Time requests

enum TimeRequestStatus: String, Codable {
    case pending
    case approved
    case denied
}

/// PROTOCOL.md: `{ id, packageName, appName, minutesRequested, message?,
/// status, grantedMinutes?, responseNote?, createdAt, respondedAt? }`.
///
/// Note there is no `deviceId`: a request is always read over a connection to
/// exactly one child device, so the owning device is implied by the
/// `DeviceSession` that fetched it.
struct TimeRequest: Decodable, Identifiable, Equatable {
    let id: String
    let packageName: String
    let appName: String?
    let minutesRequested: Int
    let message: String?
    let status: TimeRequestStatus
    let grantedMinutes: Int?
    let responseNote: String?
    let createdAt: Date
    let respondedAt: Date?

    var displayName: String { appName ?? packageName }

    private enum CodingKeys: String, CodingKey {
        case id, packageName, appName, minutesRequested, message, status
        case grantedMinutes, responseNote, createdAt, respondedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        packageName = try container.decode(String.self, forKey: .packageName)
        appName = try container.decodeIfPresent(String.self, forKey: .appName)
        minutesRequested = try container.decodeIfPresent(Int.self, forKey: .minutesRequested) ?? 0
        message = try container.decodeIfPresent(String.self, forKey: .message)
        status = try container.decodeIfPresent(TimeRequestStatus.self, forKey: .status) ?? .pending
        grantedMinutes = try container.decodeIfPresent(Int.self, forKey: .grantedMinutes)
        responseNote = try container.decodeIfPresent(String.self, forKey: .responseNote)
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        respondedAt = try container.decodeIfPresent(Date.self, forKey: .respondedAt)
    }
}

struct ApproveRequestBody: Encodable {
    let grantedMinutes: Int
}

struct DenyRequestBody: Encodable {
    let reason: String?
}

// MARK: - WebSocket events (`/events`)

/// Payload of `usage:update`.
struct UsageUpdatePayload: Decodable, Equatable {
    let packageName: String
    let minutesUsed: Int
    let date: String
}

/// Payload of `policy:update`. `policies` is keyed by package name in the
/// same shape `GET /apps` nests, but sent as a standalone map/array is not
/// specified — we accept either an array of `{ packageName, ... }` objects or
/// an object keyed by package name.
struct PolicyUpdatePayload: Decodable, Equatable {
    let policies: [String: AppPolicy]
    let schedule: [ScheduleWindow]

    private struct KeyedPolicy: Decodable {
        let packageName: String
        let dailyLimitMinutes: Int?
        let blocked: Bool?
    }

    private enum CodingKeys: String, CodingKey {
        case policies, schedule
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schedule = try container.decodeIfPresent([ScheduleWindow].self, forKey: .schedule) ?? []

        if let map = try? container.decodeIfPresent([String: AppPolicy].self, forKey: .policies) {
            policies = map
        } else if let list = try? container.decodeIfPresent([KeyedPolicy].self, forKey: .policies) {
            policies = Dictionary(
                uniqueKeysWithValues: list.map {
                    ($0.packageName, AppPolicy(dailyLimitMinutes: $0.dailyLimitMinutes, blocked: $0.blocked ?? false))
                }
            )
        } else {
            policies = [:]
        }
    }
}

/// Payload of `device:state`, which doubles as the ~60s keepalive and is a
/// second source of learned endpoints.
struct DeviceStatePayload: Decodable, Equatable {
    let batteryLevel: Int?
    let endpoints: [String]

    private enum CodingKeys: String, CodingKey {
        case batteryLevel, endpoints
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        batteryLevel = try container.decodeIfPresent(Int.self, forKey: .batteryLevel)
        endpoints = try container.decodeIfPresent([String].self, forKey: .endpoints) ?? []
    }
}

/// One `{ type, payload }` message from the child.
enum DeviceEvent: Decodable {
    case requestNew(TimeRequest)
    case usageUpdate(UsageUpdatePayload)
    case policyUpdate(PolicyUpdatePayload)
    case deviceState(DeviceStatePayload)
    /// A type we don't know about. Forward-compatible by design: an older
    /// parent app talking to a newer child must not drop the connection.
    case unknown(type: String)

    private enum CodingKeys: String, CodingKey {
        case type, payload
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        switch type {
        case "request:new":
            self = .requestNew(try container.decode(TimeRequest.self, forKey: .payload))
        case "usage:update":
            self = .usageUpdate(try container.decode(UsageUpdatePayload.self, forKey: .payload))
        case "policy:update":
            self = .policyUpdate(try container.decode(PolicyUpdatePayload.self, forKey: .payload))
        case "device:state":
            self = .deviceState(try container.decode(DeviceStatePayload.self, forKey: .payload))
        default:
            self = .unknown(type: type)
        }
    }
}
