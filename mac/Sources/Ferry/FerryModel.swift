import AppKit
import Network
import UserNotifications

struct Peer: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var token: String
    /// Last address the phone reached us from; used when Bonjour can't see it.
    var host: String?
    var port: UInt16?
}

struct Activity: Identifiable {
    let id = UUID()
    let incoming: Bool
    let title: String
    let detail: String
    let fileURL: URL?
    /// Full text of clipboard items, so clicking one can copy it again.
    var text: String? = nil
    let date = Date()
}

/// What "Send clipboard" would send, in the order it checks.
enum ClipboardContent {
    case files([URL])
    case image(NSImage, pixelWidth: Int, pixelHeight: Int)
    case text(String)
    case empty

    static func read(_ pasteboard: NSPasteboard = .general) -> ClipboardContent {
        // Finder puts the file names and icons on the pasteboard too, so check for files first.
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
           !urls.isEmpty {
            return .files(urls)
        }
        if pasteboard.canReadObject(forClasses: [NSImage.self], options: nil), let image = NSImage(pasteboard: pasteboard) {
            let rep = image.representations.first
            return .image(image, pixelWidth: rep?.pixelsWide ?? Int(image.size.width), pixelHeight: rep?.pixelsHigh ?? Int(image.size.height))
        }
        if let text = pasteboard.string(forType: .string), !text.isEmpty {
            return .text(text)
        }
        return .empty
    }
}

@MainActor
final class FerryModel: ObservableObject {
    static let shared = FerryModel()
    nonisolated static let preferredPort: UInt16 = 47820
    nonisolated static let maxTextSize: Int64 = 10 * 1024 * 1024

    @Published private(set) var peers: [Peer] = []
    @Published private(set) var onlineEndpoints: [String: NWEndpoint] = [:]
    @Published private(set) var activity: [Activity] = []
    /// The Recent item just copied again, which shows "Copied" for a moment.
    @Published private(set) var copiedActivityID: UUID?
    @Published private(set) var busy: String?
    @Published var lastError: String?
    @Published var selectedPeerID: String?
    @Published private(set) var listening = false
    @Published private(set) var clipboard: ClipboardContent = .empty
    private var clipboardChangeCount = -1

    let deviceID: String
    let deviceName = Host.current().localizedName ?? "Mac"
    private(set) var port: UInt16 = 0

    private var listener: NWListener?
    private var browser: NWBrowser?
    private var started = false

    private init() {
        let defaults = UserDefaults.standard
        if let id = defaults.string(forKey: "deviceID") {
            deviceID = id
        } else {
            deviceID = UUID().uuidString
            defaults.set(deviceID, forKey: "deviceID")
        }
        if let data = defaults.data(forKey: "peers"), let saved = try? JSONDecoder().decode([Peer].self, from: data) {
            peers = saved
        }
        selectedPeerID = peers.first?.id
    }

    func start() {
        guard !started else { return }
        started = true
        startListener(port: Self.preferredPort)
        startBrowser()
        if Bundle.main.bundleIdentifier != nil {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    // MARK: - Peers

    var targetPeer: Peer? {
        peers.first { $0.id == selectedPeerID } ?? peers.first
    }

    func isOnline(_ peer: Peer) -> Bool {
        onlineEndpoints[peer.id] != nil
    }

    func forget(_ peer: Peer) {
        peers.removeAll { $0.id == peer.id }
        if selectedPeerID == peer.id { selectedPeerID = peers.first?.id }
        savePeers()
    }

    private func savePeers() {
        if let data = try? JSONEncoder().encode(peers) {
            UserDefaults.standard.set(data, forKey: "peers")
        }
    }

    private func endpoint(for peer: Peer) -> NWEndpoint? {
        if let found = onlineEndpoints[peer.id] { return found }
        if let host = peer.host, let port = peer.port, let nwPort = NWEndpoint.Port(rawValue: port) {
            return .hostPort(host: NWEndpoint.Host(host), port: nwPort)
        }
        return nil
    }

    // MARK: - Listening

    private func startListener(port: UInt16?) {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener: NWListener
        do {
            if let port, let nwPort = NWEndpoint.Port(rawValue: port) {
                listener = try NWListener(using: parameters, on: nwPort)
            } else {
                listener = try NWListener(using: parameters)
            }
        } catch {
            if port != nil { startListener(port: nil) } else { lastError = "Ferry couldn't start listening on the network." }
            return
        }

        var txt = NWTXTRecord()
        txt["id"] = deviceID
        txt["name"] = deviceName
        txt["kind"] = "mac"
        listener.service = NWListener.Service(name: deviceName, type: Wire.serviceType, domain: nil, txtRecord: txt)

        listener.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                guard let self else { return }
                switch state {
                case .ready:
                    self.port = listener.port?.rawValue ?? 0
                    self.listening = true
                case .failed:
                    self.listening = false
                    listener.cancel()
                    // The preferred port is taken: take any free one; Bonjour tells the phone which.
                    if port != nil { self.startListener(port: nil) }
                default:
                    break
                }
            }
        }
        listener.newConnectionHandler = { connection in
            Task.detached { await Inbox.handle(Wire(connection)) }
        }
        listener.start(queue: .main)
        self.listener = listener
    }

    private func startBrowser() {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Wire.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                var found: [String: NWEndpoint] = [:]
                for result in results {
                    guard case .bonjour(let txt) = result.metadata, let id = txt["id"], id != self.deviceID else { continue }
                    found[id] = result.endpoint
                }
                self.onlineEndpoints = found
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    // MARK: - Incoming (called by Inbox)

    func approvePairing(name: String, code: String) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Pair with \(name)?"
        alert.informativeText = "Check that \(name) shows the code \(code). Once paired, it can send files and clipboard text to this Mac."
        alert.addButton(withTitle: "Allow")
        alert.addButton(withTitle: "Don't Allow")
        return alert.runModal() == .alertFirstButtonReturn
    }

    func addPeer(id: String, name: String, host: String?, port: UInt16?) -> Peer {
        let token = (0..<32).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let peer = Peer(id: id, name: name, token: token, host: host, port: port)
        peers.removeAll { $0.id == id }
        peers.insert(peer, at: 0)
        selectedPeerID = id
        savePeers()
        record(Activity(incoming: true, title: "Paired with \(name)", detail: "", fileURL: nil))
        return peer
    }

    /// Returns the paired peer if the header carries its token, refreshing its last known address.
    func authenticate(_ header: [String: Any], host: String?) -> Peer? {
        guard let id = header["id"] as? String, let token = header["token"] as? String,
              let index = peers.firstIndex(where: { $0.id == id && $0.token == token }) else { return nil }
        if let host { peers[index].host = host }
        if let port = (header["port"] as? NSNumber)?.uint16Value, port > 0 { peers[index].port = port }
        savePeers()
        return peers[index]
    }

    func receivedText(_ text: String, from peer: Peer) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        record(Activity(incoming: true, title: "Clipboard from \(peer.name)", detail: preview(text), fileURL: nil, text: text))
        notify(title: "Copied from \(peer.name)", body: preview(text))
    }

    /// `toClipboard` is set for images the phone sent from its clipboard: they go on the pasteboard too.
    func receivedFile(_ url: URL, from peer: Peer, toClipboard: Bool) {
        if toClipboard, let image = NSImage(contentsOf: url) {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.writeObjects([image])
            record(Activity(incoming: true, title: "Image from \(peer.name)", detail: "On the clipboard · \(url.lastPathComponent)", fileURL: url))
            notify(title: "Image copied from \(peer.name)", body: "Paste it anywhere. Also saved in Downloads.")
            return
        }
        record(Activity(incoming: true, title: url.lastPathComponent, detail: "From \(peer.name) · Downloads", fileURL: url))
        notify(title: "Received from \(peer.name)", body: url.lastPathComponent)
    }

    // MARK: - Sending

    /// Re-reads the clipboard for the preview, only when something new was copied.
    func refreshClipboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != clipboardChangeCount else { return }
        clipboardChangeCount = pasteboard.changeCount
        clipboard = ClipboardContent.read(pasteboard)
    }

    /// Sends whatever is on the clipboard: files copied in Finder, else an image (e.g. a screenshot), else text.
    func sendClipboard() {
        let pasteboard = NSPasteboard.general
        switch ClipboardContent.read(pasteboard) {
        case .files(let urls):
            sendFiles(urls)
        case .image:
            if let png = Self.clipboardPNG(pasteboard) {
                sendImage(png)
            } else {
                lastError = "This image can't be sent. Try saving it as a file and sending that."
            }
        case .text(let text):
            sendText(text)
        case .empty:
            lastError = "The clipboard is empty. Copy some text, an image or files first."
        }
    }

    private static func clipboardPNG(_ pasteboard: NSPasteboard) -> Data? {
        if let png = pasteboard.data(forType: .png) { return png }
        // Other formats (TIFF, JPEG, HEIC…) go through NSImage so the phone always gets a PNG.
        guard let tiff = NSImage(pasteboard: pasteboard)?.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) else { return nil }
        return bitmap.representation(using: .png, properties: [:])
    }

    private func sendImage(_ png: Data) {
        guard let (peer, endpoint) = target() else { return }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd 'at' HH.mm.ss"
        let name = "Clipboard \(formatter.string(from: Date())).png"
        perform("Sending image…") { [deviceID, port] in
            try await Outbox.send(to: endpoint, header: ["type": "file", "name": name, "clip": true],
                                  peer: peer, myID: deviceID, myPort: port, size: Int64(png.count)) { wire in
                try await wire.send(png)
            }
        } done: { [weak self] in
            self?.record(Activity(incoming: false, title: "Image to \(peer.name)", detail: "From the clipboard", fileURL: nil))
        }
    }

    func sendText(_ text: String) {
        guard let (peer, endpoint) = target() else { return }
        let data = Data(text.utf8)
        perform("Sending clipboard…") { [deviceID, port] in
            try await Outbox.send(to: endpoint, header: ["type": "text", "clip": true],
                                  peer: peer, myID: deviceID, myPort: port, size: Int64(data.count)) { wire in
                try await wire.send(data)
            }
        } done: { [weak self] in
            self?.record(Activity(incoming: false, title: "Clipboard to \(peer.name)", detail: self?.preview(text) ?? "", fileURL: nil, text: text))
        }
    }

    func chooseAndSendFiles() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }

    func sendFiles(_ urls: [URL]) {
        let files = urls.filter { !$0.hasDirectoryPath }
        if files.count < urls.count { lastError = "Folders can't be sent yet. Send the files inside instead." }
        guard !files.isEmpty, let (peer, endpoint) = target() else { return }
        for url in files {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map(Int64.init) ?? 0
            perform("Sending \(url.lastPathComponent)…") { [deviceID, port] in
                try await Outbox.send(to: endpoint, header: ["type": "file", "name": url.lastPathComponent],
                                      peer: peer, myID: deviceID, myPort: port, size: size) { wire in
                    try await Outbox.stream(url, to: wire)
                }
            } done: { [weak self] in
                self?.record(Activity(incoming: false, title: url.lastPathComponent, detail: "To \(peer.name)", fileURL: url))
            }
        }
    }

    // MARK: - Share extension relay

    /// Forwards a file handed over by the Share extension; finishes once the phone has it. Returns the phone's name.
    func relayFile(_ url: URL, name: String) async throws -> String {
        guard let (peer, endpoint) = target() else { throw WireError.refused(lastError ?? "Pair a phone first.") }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { $0 }.map(Int64.init) ?? 0
        busy = "Sending \(name)…"
        defer { busy = nil }
        try await Outbox.send(to: endpoint, header: ["type": "file", "name": name],
                              peer: peer, myID: deviceID, myPort: port, size: size) { wire in
            try await Outbox.stream(url, to: wire)
        }
        record(Activity(incoming: false, title: name, detail: "To \(peer.name) · Shared", fileURL: nil))
        return peer.name
    }

    /// Forwards text or a link from the Share extension to the phone's clipboard.
    func relayText(_ text: String) async throws -> String {
        guard let (peer, endpoint) = target() else { throw WireError.refused(lastError ?? "Pair a phone first.") }
        let data = Data(text.utf8)
        busy = "Sending text…"
        defer { busy = nil }
        try await Outbox.send(to: endpoint, header: ["type": "text", "clip": true],
                              peer: peer, myID: deviceID, myPort: port, size: Int64(data.count)) { wire in
            try await wire.send(data)
        }
        record(Activity(incoming: false, title: "Text to \(peer.name)", detail: preview(text), fileURL: nil, text: text))
        return peer.name
    }

    private func target() -> (Peer, NWEndpoint)? {
        guard let peer = targetPeer else {
            lastError = "Pair a phone first."
            return nil
        }
        guard let endpoint = endpoint(for: peer) else {
            lastError = "\(peer.name) isn't on this Wi-Fi. Open Ferry on the phone and try again."
            return nil
        }
        return (peer, endpoint)
    }

    /// Runs transfers one after another so the status line stays meaningful.
    private var queueTail: Task<Void, Never>?

    private func perform(_ label: String, _ work: @escaping () async throws -> Void, done: @escaping () -> Void) {
        let previous = queueTail
        queueTail = Task { @MainActor in
            await previous?.value
            busy = label
            lastError = nil
            do {
                try await work()
                done()
            } catch {
                lastError = "Couldn't send: \(error.localizedDescription)"
            }
            busy = nil
        }
    }

    // MARK: - Helpers

    /// Clicking a Recent item: shows the file in Finder, or copies the text again.
    func open(_ item: Activity) {
        if let url = item.fileURL {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        } else if let text = item.text {
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            refreshClipboard()
            copiedActivityID = item.id
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(1.5))
                if self?.copiedActivityID == item.id { self?.copiedActivityID = nil }
            }
        }
    }

    private func record(_ item: Activity) {
        activity.insert(item, at: 0)
        if activity.count > 8 { activity.removeLast(activity.count - 8) }
    }

    private func preview(_ text: String) -> String {
        let line = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? ""
        return line.count > 60 ? String(line.prefix(60)) + "…" : line
    }

    private func notify(title: String, body: String) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
