import Foundation
import Network

enum WireError: LocalizedError {
    case closed
    case refused(String)

    var errorDescription: String? {
        switch self {
        case .closed: return "The connection closed early."
        case .refused(let reason): return reason
        }
    }
}

/// One Ferry request on a TCP connection: a JSON header line, then `size` raw payload bytes.
final class Wire {
    static let serviceType = "_ferry._tcp"

    let connection: NWConnection
    private var buffer = Data()
    private let queue = DispatchQueue(label: "ferry.wire")

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    static func connect(to endpoint: NWEndpoint) -> Wire {
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 5
        return Wire(NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp)))
    }

    func open() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var resumed = false
            connection.stateUpdateHandler = { state in
                guard !resumed else { return }
                switch state {
                case .ready:
                    resumed = true
                    continuation.resume()
                case .failed(let error), .waiting(let error):
                    resumed = true
                    continuation.resume(throwing: error)
                case .cancelled:
                    resumed = true
                    continuation.resume(throwing: WireError.closed)
                default:
                    break
                }
            }
            connection.start(queue: queue)
        }
    }

    func close() {
        connection.cancel()
    }

    func readHeader() async throws -> [String: Any] {
        while true {
            if let newline = buffer.firstIndex(of: 0x0A) {
                let line = buffer[buffer.startIndex..<newline]
                buffer = Data(buffer[(newline + 1)...])
                guard let header = try JSONSerialization.jsonObject(with: line) as? [String: Any] else {
                    throw WireError.refused("The other device sent an unreadable request.")
                }
                return header
            }
            if buffer.count > 64 * 1024 { throw WireError.refused("The request header is too long.") }
            buffer.append(try await receive())
        }
    }

    /// Hands exactly `size` payload bytes to `sink`, a chunk at a time.
    func readPayload(size: Int64, into sink: (Data) throws -> Void) async throws {
        var remaining = size
        while remaining > 0 {
            if buffer.isEmpty { buffer = try await receive() }
            let take = Int(min(Int64(buffer.count), remaining))
            try sink(buffer.prefix(take))
            buffer = Data(buffer.dropFirst(take))
            remaining -= Int64(take)
        }
    }

    func sendHeader(_ header: [String: Any]) async throws {
        var data = try JSONSerialization.data(withJSONObject: header)
        data.append(0x0A)
        try await send(data)
    }

    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            })
        }
    }

    private func receive() async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { data, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    continuation.resume(returning: data)
                } else if isComplete {
                    continuation.resume(throwing: WireError.closed)
                } else {
                    continuation.resume(returning: Data())
                }
            }
        }
    }
}
