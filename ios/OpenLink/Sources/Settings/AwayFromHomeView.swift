//
//  AwayFromHomeView.swift
//  OpenLink (parent app)
//
//  The only setup step OpenLink can't do for you: putting both phones on the
//  same private network so this app can reach the child device from outside
//  the house.
//
//  The mechanism is already automatic — the child reports its addresses on
//  every `GET /device` and DeviceSession persists them (see
//  Model/DeviceEndpoint.swift). What was missing was anywhere in the app that
//  said so, or that told the user installing Tailscale on both phones is the
//  entire job. Everything here is plain prose on purpose: the reader is a
//  parent, not a network engineer.
//
//  Deliberately iOS 15-compatible: NavigationView rather than NavigationStack,
//  no LabeledContent, no presentationDetents.
//

import SwiftUI

/// The guidance itself, as `Section`s, so it can sit inside any `Form` — the
/// dedicated screen below, and the manual-entry escape hatch in
/// `Devices/EndpointsView.swift`, which is where someone who is struggling
/// with away-from-home access tends to end up first.
struct AwayFromHomeSections: View {
    var body: some View {
        Group {
            Section("At Home") {
                Text("When your phone and your child's phone are on the same Wi-Fi, they find each other on their own. There's nothing to set up.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Anywhere Else") {
                Text("Install Tailscale on both phones — yours and your child's — and sign in to the same account on each. That's the whole setup.")
                    .font(.footnote)
                Text("Tailscale is a separate app from another company, and installing it is your choice. Its free plan is more than enough for a family. If you'd rather run WireGuard yourself, that works exactly the same way.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Nothing To Configure Here") {
                Text("Once Tailscale is signed in on both phones, OpenLink finds your child's new address by itself and remembers it. You never type an address in.")
                    .font(.footnote)
                Text("Do this once while both phones are together on your home Wi-Fi, and open this app before you go out, so it has already learned the new address.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("You can tell it worked from the child's phone: in OpenLink there, Settings → Away from home lists an address starting with 100.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct AwayFromHomeView: View {
    var body: some View {
        Form {
            AwayFromHomeSections()
        }
        .navigationTitle("Away From Home")
        .navigationBarTitleDisplayMode(.inline)
    }
}
