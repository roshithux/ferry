import Foundation
import Network

/// Handles one incoming request from a phone.
enum Inbox {
    static func handle(_ wire: Wire) async {
        defer { wire.close() }
        do {
            try await wire.open()
            let header = try await wire.readHeader()
            let host = remoteHost(of: wire.connection)
            let model = await FerryModel.shared

            if header["type"] as? String == "pair" {
                try await wire.sendHeader(await pair(header, host: host))
                return
            }

            // The Share extension hands items over on this Mac only; never accept this from the network.
            if header["type"] as? String == "share" {
                guard isLoopback(wire.connection) else {
                    try await wire.sendHeader(["ok": false, "error": "Sharing is only accepted from this Mac."])
                    return
                }
                try await wire.sendHeader(await share(header, wire: wire))
                return
            }

            guard let peer = await model.authenticate(header, host: host) else {
                try await wire.sendHeader(["ok": false, "error": "This phone isn't paired with the Mac. Pair again."])
                return
            }
            let size = (header["size"] as? NSNumber)?.int64Value ?? 0

            switch header["type"] as? String {
            case "text":
                guard size <= FerryModel.maxTextSize else {
                    try await wire.sendHeader(["ok": false, "error": "That text is too long to send."])
                    return
                }
                var data = Data()
                try await wire.readPayload(size: size) { data.append($0) }
                let text = String(decoding: data, as: UTF8.self)
                await model.receivedText(text, from: peer)
            case "file":
                let url = try uniqueDownloadURL(for: header["name"] as? String)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let handle = try FileHandle(forWritingTo: url)
                do {
                    try await wire.readPayload(size: size) { try handle.write(contentsOf: $0) }
                    try handle.close()
                } catch {
                    try? handle.close()
                    try? FileManager.default.removeItem(at: url)
                    throw error
                }
                await model.receivedFile(url, from: peer, toClipboard: header["clip"] as? Bool == true)
            default:
                try await wire.sendHeader(["ok": false, "error": "This Mac doesn't understand that request. Update Ferry."])
                return
            }
            try await wire.sendHeader(["ok": true])
        } catch {
            NSLog("Ferry: incoming transfer failed: \(error)")
        }
    }

    private static func pair(_ header: [String: Any], host: String?) async -> [String: Any] {
        guard let id = header["id"] as? String, let name = header["name"] as? String,
              let code = header["code"] as? String else {
            return ["ok": false, "error": "The pairing request was incomplete."]
        }
        let port = (header["port"] as? NSNumber)?.uint16Value
        let model = await FerryModel.shared
        guard await model.approvePairing(name: name, code: code) else {
            return ["ok": false, "error": "Pairing was declined on the Mac."]
        }
        let peer = await model.addPeer(id: id, name: name, host: host, port: port)
        return ["ok": true, "id": model.deviceID, "name": model.deviceName, "token": peer.token]
    }

    /// Receives an item from the Share extension and forwards it to the phone before replying,
    /// so the share sheet can say whether it arrived.
    private static func share(_ header: [String: Any], wire: Wire) async -> [String: Any] {
        let size = (header["size"] as? NSNumber)?.int64Value ?? 0
        let model = await FerryModel.shared
        do {
            switch header["kind"] as? String {
            case "text":
                guard size <= FerryModel.maxTextSize else { throw WireError.refused("That text is too long to send.") }
                var data = Data()
                try await wire.readPayload(size: size) { data.append($0) }
                let phone = try await model.relayText(String(decoding: data, as: UTF8.self))
                return ["ok": true, "phone": phone]
            case "file":
                let rawName = (header["name"] as? String).map { ($0 as NSString).lastPathComponent } ?? ""
                let name = rawName.isEmpty || rawName.hasPrefix(".") ? "Shared file" : rawName
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ferry-share-\(UUID().uuidString)")
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                defer { try? FileManager.default.removeItem(at: folder) }
                let url = folder.appendingPathComponent(name)
                FileManager.default.createFile(atPath: url.path, contents: nil)
                let handle = try FileHandle(forWritingTo: url)
                do {
                    try await wire.readPayload(size: size) { try handle.write(contentsOf: $0) }
                    try handle.close()
                } catch {
                    try? handle.close()
                    throw error
                }
                let phone = try await model.relayFile(url, name: name)
                return ["ok": true, "phone": phone]
            default:
                throw WireError.refused("Ferry doesn't understand that shared item.")
            }
        } catch {
            return ["ok": false, "error": error.localizedDescription]
        }
    }

    private static func isLoopback(_ connection: NWConnection) -> Bool {
        guard case .hostPort(let host, _) = connection.endpoint else { return false }
        switch host {
        case .ipv4(let address): return address.isLoopback
        case .ipv6(let address): return address.isLoopback
        default: return false
        }
    }

    private static func remoteHost(of connection: NWConnection) -> String? {
        guard case .hostPort(let host, _) = connection.endpoint else { return nil }
        switch host {
        case .ipv4(let address): return "\(address)"
        case .ipv6(let address): return "\(address)"
        case .name(let name, _): return name
        @unknown default: return nil
        }
    }

    /// `~/Downloads/<name>`, with " 2", " 3"… added when the name is taken.
    private static func uniqueDownloadURL(for rawName: String?) throws -> URL {
        let downloads = try FileManager.default.url(for: .downloadsDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var name = (rawName.map { ($0 as NSString).lastPathComponent } ?? "").replacingOccurrences(of: ":", with: "-")
        if name.isEmpty || name.hasPrefix(".") { name = "Ferry file" + name }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var url = downloads.appendingPathComponent(name)
        var counter = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = downloads.appendingPathComponent(ext.isEmpty ? "\(base) \(counter)" : "\(base) \(counter).\(ext)")
            counter += 1
        }
        return url
    }
}

/// Sends one request to a paired phone.
enum Outbox {
    /// Writes a file to the wire in chunks, so large files never sit in memory.
    static func stream(_ url: URL, to wire: Wire) async throws {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        while let chunk = try handle.read(upToCount: 256 * 1024), !chunk.isEmpty {
            try await wire.send(chunk)
        }
    }

    static func send(to endpoint: NWEndpoint, header: [String: Any], peer: Peer, myID: String, myPort: UInt16,
                     size: Int64, body: (Wire) async throws -> Void) async throws {
        let wire = Wire.connect(to: endpoint)
        defer { wire.close() }
        try await wire.open()
        var full = header
        full["v"] = 1
        full["id"] = myID
        full["token"] = peer.token
        full["port"] = Int(myPort)
        full["size"] = size
        try await wire.sendHeader(full)
        try await body(wire)
        let reply = try await wire.readHeader()
        guard reply["ok"] as? Bool == true else {
            throw WireError.refused(reply["error"] as? String ?? "\(peer.name) refused the transfer.")
        }
    }
}
