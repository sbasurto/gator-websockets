import Foundation

public final class GatorWebSocketClient {
    public var onState: (String) -> Void = { _ in }
    public var onMessage: ([String: Any]) -> Void = { _ in }
    public var onEvent: ([String: Any]) -> Void = { _ in }
    public var onError: (Error) -> Void = { _ in }

    public private(set) var authenticated = false

    private let url: URL
    private let queue = DispatchQueue(label: "gator.websocket.client")
    private var task: URLSessionWebSocketTask?
    private var session: GatorProtocol.Session?
    private var usuario: String?
    private var passphrase: String?

    public init(url: URL) {
        self.url = url
    }

    public func connect(usuario: String, passphrase: String) {
        queue.async {
            guard self.task == nil else { return self.fail(ClientError.alreadyConnected) }
            self.usuario = usuario
            self.passphrase = passphrase
            self.task = URLSession.shared.webSocketTask(with: self.url)
            self.task?.resume()
            self.onState("connected")
            self.receiveNext()
        }
    }

    public func send(_ message: [String: Any]) {
        queue.async {
            guard self.authenticated, let session = self.session else {
                return self.fail(ClientError.notAuthenticated)
            }
            do {
                let json = try Self.jsonString(message)
                self.task?.send(.string(try session.seal(json))) { [weak self] error in
                    if let error { self?.queue.async { self?.fail(error) } }
                }
            } catch {
                self.fail(error)
            }
        }
    }

    public func close() {
        queue.async {
            self.task?.cancel(with: .normalClosure, reason: nil)
            self.reset()
        }
    }

    private func receiveNext() {
        task?.receive { [weak self] result in
            self?.queue.async { [weak self] in
                guard let self, self.task != nil else { return }
                switch result {
                case .success(.string(let text)):
                    do {
                        try self.process(text)
                        self.receiveNext()
                    } catch {
                        self.fail(error)
                    }
                case .success:
                    self.fail(ClientError.textMessageRequired)
                case .failure(let error):
                    self.fail(error)
                }
            }
        }
    }

    private func process(_ text: String) throws {
        if session == nil {
            let offer = try Self.jsonObject(text)
            guard offer["type"] as? String == "askauth",
                  let data = offer["data"] as? [String: Any],
                  data["version"] as? String == "1",
                  data["suite"] as? String == GatorProtocol.suite,
                  let keyId = data["keyId"] as? String,
                  let publicKey = offer["keyForAuth"] as? String,
                  let usuario, let passphrase else {
                throw GatorProtocol.ProtocolError.unsupportedSuite
            }
            let authentication = try JSONSerialization.data(withJSONObject: [
                "type": "authenticateme", "message": passphrase, "data": ["usuario": usuario]
            ])
            session = try GatorProtocol.start(keyId: keyId, publicKey: publicKey, authentication: authentication)
            task?.send(.string(session!.initialEnvelope)) { [weak self] error in
                if let error { self?.queue.async { self?.fail(error) } }
            }
            self.usuario = nil
            self.passphrase = nil
            return
        }

        let message = try Self.jsonObject(try session!.open(text))
        switch message["type"] as? String {
        case "authsuccess":
            authenticated = true
            onState("authenticated")
        case "forcedclosure":
            throw ClientError.connectionRejected(message["estatusDesc"] as? String ?? "Connection rejected")
        case "event":
            onEvent(message)
        default:
            onMessage(message)
        }
    }

    private func fail(_ error: Error) {
        onError(error)
        task?.cancel(with: .protocolError, reason: nil)
        reset()
    }

    private func reset() {
        authenticated = false
        session = nil
        task = nil
        usuario = nil
        passphrase = nil
        onState("closed")
    }

    private static func jsonString(_ value: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value)
        guard let string = String(data: data, encoding: .utf8) else { throw ClientError.invalidJSON }
        return string
    }

    private static func jsonObject(_ value: String) throws -> [String: Any] {
        guard let data = value.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ClientError.invalidJSON
        }
        return object
    }

    public enum ClientError: Error {
        case alreadyConnected, notAuthenticated, textMessageRequired, invalidJSON
        case connectionRejected(String)
    }
}
