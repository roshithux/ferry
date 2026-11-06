import SwiftUI
import UniformTypeIdentifiers

@main
struct FerryApp: App {
    @NSApplicationDelegateAdaptor private var appDelegate: AppDelegate
    @ObservedObject private var model = FerryModel.shared

    init() {
        FerryModel.shared.start()
    }

    var body: some Scene {
        MenuBarExtra {
            MenuView(model: model)
        } label: {
            Image(systemName: model.busy == nil ? "arrow.left.arrow.right.circle" : "arrow.left.arrow.right.circle.fill")
        }
        .menuBarExtraStyle(.window)
    }
}

/// macOS can hide menu bar icons (full menu bar, or Menu Bar settings), so opening Ferry.app
/// also shows the same panel in a normal window: on launch and whenever it's opened again.
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        showWindow()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showWindow()
        return false
    }

    @MainActor
    private func showWindow() {
        if window == nil {
            let controller = NSHostingController(rootView: ScrollView { MenuView(model: FerryModel.shared) })
            // The window must not size itself from the content: with wrapping text the two keep
            // re-measuring each other and AppKit aborts. Fixed width, user-adjustable height, content scrolls.
            controller.sizingOptions = []
            let window = NSWindow(contentViewController: controller)
            window.title = "Ferry"
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            window.setContentSize(NSSize(width: 320, height: 620))
            window.contentMinSize = NSSize(width: 320, height: 320)
            window.contentMaxSize = NSSize(width: 320, height: 2000)
            window.isReleasedWhenClosed = false
            window.center()
            self.window = window
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

struct MenuView: View {
    @ObservedObject var model: FerryModel
    @State private var dropTargeted = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Ferry").font(.headline)
                Spacer()
                Label(model.listening ? "Ready" : "Starting…", systemImage: model.listening ? "wifi" : "wifi.exclamationmark")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if model.peers.isEmpty {
                pairingHelp
            } else {
                peerPicker
                actions
                dropZone
            }

            if let busy = model.busy {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(busy).font(.caption)
                }
            }
            if let error = model.lastError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !model.activity.isEmpty { recent }

            Divider()
            HStack {
                if let peer = model.targetPeer {
                    Button("Forget \(peer.name)") { model.forget(peer) }
                        .buttonStyle(.link)
                        .font(.caption)
                }
                Spacer()
                Button("Quit Ferry") { NSApp.terminate(nil) }
                    .keyboardShortcut("q")
                    .font(.caption)
            }
        }
        .padding(14)
        .frame(width: 320)
        // Only the change count is checked each tick; the clipboard is read when it changes.
        .onAppear { model.refreshClipboard() }
        .onReceive(Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()) { _ in model.refreshClipboard() }
    }

    private var pairingHelp: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("No phone paired yet", systemImage: "iphone.slash").font(.subheadline.weight(.semibold))
            Text("Open Ferry on your Android phone, on the same Wi-Fi, and tap **\(model.deviceName)**. This Mac will ask you to allow it.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var peerPicker: some View {
        HStack(spacing: 8) {
            Image(systemName: "smartphone")
            if model.peers.count > 1 {
                Picker("Phone", selection: $model.selectedPeerID) {
                    ForEach(model.peers) { Text($0.name).tag(Optional($0.id)) }
                }
                .labelsHidden()
            } else if let peer = model.targetPeer {
                Text(peer.name).font(.subheadline.weight(.semibold))
            }
            Spacer()
            if let peer = model.targetPeer {
                let online = model.isOnline(peer)
                Label(online ? "On this Wi-Fi" : "Not found", systemImage: online ? "checkmark.circle.fill" : "circle.dashed")
                    .font(.caption)
                    .foregroundStyle(online ? .green : .secondary)
            }
        }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 8) {
            ClipboardPreview(content: model.clipboard)
            Button { model.sendClipboard() } label: {
                Label("Send clipboard", systemImage: "doc.on.clipboard")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(model.clipboard.isEmpty)
            Button { model.chooseAndSendFiles() } label: {
                Label("Send files…", systemImage: "paperplane")
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private var dropZone: some View {
        RoundedRectangle(cornerRadius: 10)
            .strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [5]))
            .foregroundStyle(dropTargeted ? Color.accentColor : Color.secondary.opacity(0.5))
            .background(RoundedRectangle(cornerRadius: 10).fill(dropTargeted ? Color.accentColor.opacity(0.1) : .clear))
            .frame(height: 64)
            .overlay(Text("Drop files here to send").font(.callout).foregroundStyle(.secondary))
            .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
                for provider in providers {
                    _ = provider.loadObject(ofClass: URL.self) { url, _ in
                        guard let url else { return }
                        Task { @MainActor in FerryModel.shared.sendFiles([url]) }
                    }
                }
                return true
            }
    }

    private var recent: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recent").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            ForEach(model.activity) { item in
                let copied = model.copiedActivityID == item.id
                Button {
                    model.open(item)
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: item.incoming ? "arrow.down.circle" : "arrow.up.circle")
                            .foregroundStyle(item.incoming ? .blue : .secondary)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(item.title).lineLimit(1).truncationMode(.middle)
                            if copied {
                                Label("Copied", systemImage: "checkmark").font(.caption).foregroundStyle(.secondary)
                            } else if !item.detail.isEmpty {
                                Text(item.detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(item.fileURL == nil && item.text == nil)
                .help(item.fileURL != nil ? "Show in Finder" : item.text != nil ? "Copy again" : "")
            }
        }
    }
}

extension ClipboardContent {
    var isEmpty: Bool {
        if case .empty = self { return true }
        return false
    }
}

/// Shows what "Send clipboard" will send.
struct ClipboardPreview: View {
    let content: ClipboardContent

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("On your clipboard").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            Group {
                switch content {
                case .image(let image, let width, let height):
                    VStack(alignment: .leading, spacing: 6) {
                        Image(nsImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: .infinity, maxHeight: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .accessibilityLabel("Image on the clipboard")
                        caption("photo", "Image · \(width) × \(height)")
                    }
                case .text(let text):
                    VStack(alignment: .leading, spacing: 6) {
                        Text(text.trimmingCharacters(in: .whitespacesAndNewlines))
                            .font(.callout)
                            .lineLimit(4)
                            .truncationMode(.tail)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                        caption("text.alignleft", text.count == 1 ? "Text · 1 character" : "Text · \(text.count) characters")
                    }
                case .files(let urls):
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(urls.prefix(3), id: \.self) { url in
                            HStack(spacing: 6) {
                                Image(nsImage: NSWorkspace.shared.icon(forFile: url.path))
                                    .resizable()
                                    .frame(width: 18, height: 18)
                                Text(url.lastPathComponent).font(.callout).lineLimit(1).truncationMode(.middle)
                            }
                        }
                        if urls.count > 3 {
                            Text("and \(urls.count - 3) more").font(.caption).foregroundStyle(.secondary)
                        }
                        caption("doc.on.doc", urls.count == 1 ? "1 file copied in Finder" : "\(urls.count) files copied in Finder")
                    }
                case .empty:
                    Text("Nothing yet. Copy text, take a screenshot (⌃⇧⌘4), or copy files in Finder.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 8).fill(Color.secondary.opacity(0.12)))
        }
    }

    private func caption(_ symbol: String, _ text: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.caption)
            .foregroundStyle(.secondary)
    }
}
