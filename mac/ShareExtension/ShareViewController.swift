import AppKit
import Network
import UniformTypeIdentifiers

/// "Share → Ferry". Hands each shared item to the Ferry app running on this Mac, which forwards it
/// to the paired phone. The extension is sandboxed, so it never sees the pairing itself.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private enum Item {
        case file(URL)
        case data(Data, name: String)
        case text(String)
    }

    private let titleLabel = NSTextField(labelWithString: "Sending to your phone…")
    private let detailLabel = NSTextField(wrappingLabelWithString: "")
    private let spinner = NSProgressIndicator()
    private let closeButton = NSButton(title: "Close", target: nil, action: nil)

    override func loadView() {
        titleLabel.font = .boldSystemFont(ofSize: NSFont.systemFontSize)
        detailLabel.textColor = .secondaryLabelColor
        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.startAnimation(nil)
        closeButton.target = self
        closeButton.action = #selector(close)
        closeButton.keyEquivalent = "\u{1b}"
        closeButton.isHidden = true

        let header = NSStackView(views: [spinner, titleLabel])
        header.spacing = 8
        let stack = NSStackView(views: [header, detailLabel, closeButton])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 8
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let container = NSView()
        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            container.widthAnchor.constraint(equalToConstant: 340),
        ])
        view = container
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        Task { await run() }
    }

    @MainActor
    private func run() async {
        do {
            let items = try await sharedItems()
            guard !items.isEmpty else { throw ShareError("There was nothing Ferry could send.") }
            var phone = "your phone"
            for (index, item) in items.enumerated() {
                detailLabel.stringValue = items.count > 1 ? "Item \(index + 1) of \(items.count)" : ""
                phone = try await relay(item)
                titleLabel.stringValue = "Sending to \(phone)…"
            }
            spinner.stopAnimation(nil)
            spinner.isHidden = true
            titleLabel.stringValue = "Sent to \(phone)"
            detailLabel.stringValue = items.count > 1 ? "\(items.count) items" : ""
            try? await Task.sleep(nanoseconds: 900_000_000)
            extensionContext?.completeRequest(returningItems: nil)
        } catch {
            spinner.stopAnimation(nil)
            spinner.isHidden = true
            titleLabel.stringValue = "Couldn't send"
            detailLabel.stringValue = error.localizedDescription
            closeButton.isHidden = false
        }
    }

    @objc private func close() {
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }

    // MARK: - Reading what was shared

    private func sharedItems() async throws -> [Item] {
        var items: [Item] = []
        for case let extensionItem as NSExtensionItem in extensionContext?.inputItems ?? [] {
            for provider in extensionItem.attachments ?? [] {
                if let item = try await item(from: provider) { items.append(item) }
            }
        }
        return items
    }

    private func item(from provider: NSItemProvider) async throws -> Item? {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
           let url = try await loadURL(provider, type: .fileURL) {
            return .file(url)
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            let loaded = try await provider.loadItem(forTypeIdentifier: UTType.image.identifier)
            if let url = loaded as? URL { return .file(url) }
            if let data = loaded as? Data { return .data(data, name: "Shared image.png") }
            if let image = loaded as? NSImage, let tiff = image.tiffRepresentation,
               let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                return .data(png, name: "Shared image.png")
            }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           let url = try await loadURL(provider, type: .url) {
            return .text(url.absoluteString)
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            let loaded = try await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier)
            if let text = loaded as? String { return .text(text) }
            if let data = loaded as? Data { return .text(String(decoding: data, as: UTF8.self)) }
        }
        return nil
    }

    private func loadURL(_ provider: NSItemProvider, type: UTType) async throws -> URL? {
        let loaded = try await provider.loadItem(forTypeIdentifier: type.identifier)
        if let url = loaded as? URL { return url }
        if let data = loaded as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        return nil
    }

    // MARK: - Handing over to the Ferry app

    /// Returns the phone's name once the Ferry app has delivered the item.
    private func relay(_ item: Item) async throws -> String {
        let wire = Wire.connect(to: .hostPort(host: "127.0.0.1", port: 47820))
        defer { wire.close() }
        do {
            try await wire.open()
        } catch {
            throw ShareError("Ferry isn't running on this Mac. Open Ferry, then share again.")
        }

        switch item {
        case .text(let text):
            let data = Data(text.utf8)
            try await wire.sendHeader(["v": 1, "type": "share", "kind": "text", "size": data.count])
            try await wire.send(data)
        case .data(let data, let name):
            try await wire.sendHeader(["v": 1, "type": "share", "kind": "file", "name": name, "size": data.count])
            try await wire.send(data)
        case .file(let url):
            if url.hasDirectoryPath { throw ShareError("Folders can't be sent yet. Share the files inside instead.") }
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            try await wire.sendHeader(["v": 1, "type": "share", "kind": "file", "name": url.lastPathComponent, "size": size])
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            while let chunk = try handle.read(upToCount: 256 * 1024), !chunk.isEmpty {
                try await wire.send(chunk)
            }
        }

        let reply = try await wire.readHeader()
        guard reply["ok"] as? Bool == true else {
            throw ShareError(reply["error"] as? String ?? "Ferry couldn't send that.")
        }
        return reply["phone"] as? String ?? "your phone"
    }
}

private struct ShareError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}
