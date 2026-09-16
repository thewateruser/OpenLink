//
//  Models.swift
//  OpenLink (parent app)
//
//  Codable request/response types matching docs/API.md exactly.
//  Field names are kept identical to the JSON the server sends/expects so
//  that no CodingKeys remapping is needed unless explicitly noted.
//

import Foundation

// MARK: - Shared JSON coding

/// Centralized JSONDecoder/JSONEncoder configured for the ISO-8601 UTC
/// timestamps used throughout docs/API.md. The server doesn't document
/// whether fractional seconds are included, so we try both on decode and
/// always emit fractional seconds on encode (a superset most servers accept).
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

// MARK: - Auth

struct RegisterRequest: Encodable {
    let email: String
    let password: String
    let familyName: String
}

struct RegisterResponse: Decodable {
    let token: String
    let familyId: String
}

struct LoginRequest: Encodable {
    let email: String
    let password: String
}

struct LoginResponse: Decodable {
    let token: String
}

// MARK: - Pairing

struct PairingGenerateResponse: Decodable {
    let code: String
    let expiresAt: Date
}

// MARK: - Devices

/// `GET /devices` row shape: name, lastSeenAt, isLocked, appCount (+ id,
/// which must be present for any per-device follow-up call even though the
/// prose in docs/API.md doesn't spell it out explicitly).
struct ChildDeviceSummary: Decodable, Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String?
    let lastSeenAt: Date?
    let isLocked: Bool
    let appCount: Int
}

/// `GET /devices/:deviceId` — full device detail including policies and
/// today's usage. docs/API.md doesn't explicitly confirm the schedule is
/// embedded here too, so `schedule` decodes leniently to `[]` if the key is
/// absent (see ios/README.md "Assumptions").
struct ChildDeviceDetail: Decodable, Identifiable {
    let id: String
    let name: String
    let platform: String?
    let timezone: String?
    let isLocked: Bool
    let lastSeenAt: Date?
    let policies: [AppPolicy]
    let usage: [UsageRecord]
    let schedule: [ScheduleWindow]

    private enum CodingKeys: String, CodingKey {
        case id, name, platform, timezone, isLocked, lastSeenAt, policies, usage, schedule
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        platform = try container.decodeIfPresent(String.self, forKey: .platform)
        timezone = try container.decodeIfPresent(String.self, forKey: .timezone)
        isLocked = try container.decode(Bool.self, forKey: .isLocked)
        lastSeenAt = try container.decodeIfPresent(Date.self, forKey: .lastSeenAt)
        policies = try container.decodeIfPresent([AppPolicy].self, forKey: .policies) ?? []
        usage = try container.decodeIfPresent([UsageRecord].self, forKey: .usage) ?? []
        schedule = try container.decodeIfPresent([ScheduleWindow].self, forKey: .schedule) ?? []
    }
}

/// Minimal shape we rely on from `POST /devices/:deviceId/lock`'s "updated
/// device" response. We only need enough to confirm the toggle applied;
/// the calling view updates its own local state rather than trusting a
/// full re-decode of an unspecified response shape.
struct DeviceLockResult: Decodable {
    let id: String
    let isLocked: Bool
}

struct LockRequest: Encodable {
    let locked: Bool
}

// MARK: - App policies

struct AppPolicy: Codable, Identifiable, Equatable {
    var policyId: String?
    var deviceId: String?
    var packageName: String
    var appName: String?
    var dailyLimitMinutes: Int?
    var blocked: Bool
    var updatedAt: Date?

    enum CodingKeys: String, CodingKey {
        case policyId = "id"
        case deviceId, packageName, appName, dailyLimitMinutes, blocked, updatedAt
    }

    /// Stable per-device identity for SwiftUI lists: packageName is unique
    /// per device and always present, unlike the server-assigned id (which
    /// doesn't exist yet for an app the parent has never touched).
    var id: String { packageName }
}

/// `PUT /devices/:deviceId/policies/:packageName` body.
///
/// `dailyLimitMinutes` is `number | null` in docs/API.md, and those two
/// states ("set to N" vs. "clear to unlimited") are semantically different
/// from "omit — don't change". Swift's synthesized Encodable can't express
/// "send an explicit JSON null", so this type encodes itself manually.
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
struct ScheduleWindow: Codable, Identifiable, Equatable {
    /// Server-assigned id, present once a window has been saved at least once.
    var serverId: String?
    /// Client-only identity so SwiftUI can track not-yet-saved rows too.
    private var clientId: String = UUID().uuidString
    var daysOfWeek: Int
    var startMinute: Int
    var endMinute: Int
    var label: String?

    var id: String { serverId ?? clientId }

    private enum CodingKeys: String, CodingKey {
        case serverId = "id"
        case daysOfWeek, startMinute, endMinute, label
    }

    init(serverId: String? = nil, daysOfWeek: Int, startMinute: Int, endMinute: Int, label: String? = nil) {
        self.serverId = serverId
        self.daysOfWeek = daysOfWeek
        self.startMinute = startMinute
        self.endMinute = endMinute
        self.label = label
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

struct ScheduleUpdateRequest: Encodable {
    let windows: [ScheduleWindow]
}

// MARK: - Usage

struct UsageRecord: Codable, Identifiable {
    var packageName: String
    var date: String
    var minutesUsed: Int
    var updatedAt: Date?

    var id: String { "\(packageName)_\(date)" }

    private enum CodingKeys: String, CodingKey {
        case packageName, date, minutesUsed, updatedAt
    }
}

// MARK: - Time requests

enum TimeRequestStatus: String, Codable {
    case pending
    case approved
    case denied
}

struct TimeRequest: Codable, Identifiable, Equatable {
    var id: String
    var deviceId: String
    var packageName: String
    var minutesRequested: Int
    var message: String?
    var status: TimeRequestStatus
    var grantedMinutes: Int?
    var respondedAt: Date?
    var createdAt: Date
}

struct ApproveRequestBody: Encodable {
    let grantedMinutes: Int
}

struct DenyRequestBody: Encodable {
    let reason: String?
}

// MARK: - Socket.IO payloads

struct DeviceHeartbeatPayload: Codable {
    let deviceId: String
    let lastSeenAt: Date
}

struct LockUpdatePayload: Codable {
    let isLocked: Bool
}

/// `policy:update` is server -> device; the parent app never receives it,
/// but the shape is included here for completeness/documentation since it
/// shares the same underlying types.
struct PolicyUpdatePayload: Codable {
    let policies: [AppPolicy]
    let schedule: [ScheduleWindow]
}
