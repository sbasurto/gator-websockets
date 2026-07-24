import Foundation

public final class GatorWebSocketClient: @unchecked Sendable {
    public enum State: String, Sendable, Equatable {
        case idle, connecting, connected, authenticating, authenticated, closed
    }

    public enum ClientError: Error, Sendable {
        case alreadyConnected
        case notAuthenticated
        case textMessageRequired
        case invalidJSON
        case invalidURL
        case connectionRejected(String)
    }

    public var onState: (String) -> Void = { _ in }
    public var onMessage: ([String: Any]) -> Void = { _ in }
    public var onEvent: ([String: Any]) -> Void = { _ in }
    public var onError: (Error) -> Void = { _ in }

    public var state: State {
        stateLock.lock()
        defer { stateLock.unlock() }
        return storedState
    }

    public var authenticated: Bool {
        state == .authenticated
    }

    private let url: URL
    private let callbackQueue: DispatchQueue
    private let keepAliveInterval: TimeInterval
    private let queue = DispatchQueue(label: "gator.websocket.client")
    private let stateLock = NSLock()
    private let transportFactory: @Sendable () -> GatorWebSocketTransport

    private var storedState: State = .idle
    private var transport: GatorWebSocketTransport?
    private var session: GatorProtocol.Session?
    private var usuario: String?
    private var passphrase: String?
    private var keepAliveTimer: DispatchSourceTimer?

    public convenience init(
        url: URL,
        callbackQueue: DispatchQueue = .main,
        keepAliveInterval: TimeInterval = 60,
        configuration: URLSessionConfiguration = .default
    ) {
        self.init(
            url: url,
            callbackQueue: callbackQueue,
            keepAliveInterval: keepAliveInterval,
            transportFactory: { URLSessionWebSocketTransport(url: url, configuration: configuration) }
        )
    }

    init(
        url: URL,
        callbackQueue: DispatchQueue,
        keepAliveInterval: TimeInterval,
        transportFactory: @escaping @Sendable () -> GatorWebSocketTransport
    ) {
        self.url = url
        self.callbackQueue = callbackQueue
        self.keepAliveInterval = keepAliveInterval
        self.transportFactory = transportFactory
    }

    public func connect(usuario: String, passphrase: String) {
        queue.async {
            guard self.transport == nil else {
                self.report(ClientError.alreadyConnected)
                return
            }
            guard let scheme = self.url.scheme?.lowercased(), scheme == "ws" || scheme == "wss" else {
                self.report(ClientError.invalidURL)
                self.transition(to: .closed)
                return
            }

            self.usuario = usuario
            self.passphrase = passphrase
            let transport = self.transportFactory()
            self.transport = transport
            self.transition(to: .connecting)
            transport.resume()
            self.receiveNext()
        }
    }

    public func send(
        _ message: [String: Any],
        completion: @escaping (Result<Void, Error>) -> Void = { _ in }
    ) {
        let message = UncheckedSendable(message)
        let completion = UncheckedSendable(completion)
        queue.async {
            guard self.authenticated, let session = self.session, let transport = self.transport else {
                let error = ClientError.notAuthenticated
                self.report(error)
                self.complete(completion.value, with: .failure(error))
                return
            }
            do {
                let envelope = try session.seal(Self.jsonString(message.value))
                transport.send(text: envelope) { [weak self] error in
                    guard let self else { return }
                    self.queue.async {
                        if let error {
                            self.complete(completion.value, with: .failure(error))
                            self.fail(error)
                        } else {
                            self.complete(completion.value, with: .success(()))
                        }
                    }
                }
            } catch {
                self.complete(completion.value, with: .failure(error))
                self.fail(error)
            }
        }
    }

    public func close(code: URLSessionWebSocketTask.CloseCode = .normalClosure, reason: String = "") {
        queue.async {
            self.transport?.cancel(code: code, reason: Self.closeReason(reason))
            self.reset()
        }
    }

    private func receiveNext() {
        transport?.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                guard self.transport != nil else { return }
                switch result {
                case .success(let text):
                    do {
                        try self.process(text)
                        self.receiveNext()
                    } catch {
                        self.fail(error)
                    }
                case .failure(let error):
                    self.fail(error)
                }
            }
        }
    }

    private func process(_ text: String) throws {
        if session == nil {
            transition(to: .connected)
            let offer = try Self.jsonObject(text)
            guard offer["type"] as? String == "askauth",
                  let data = offer["data"] as? [String: Any],
                  data["version"] as? String == "1",
                  data["suite"] as? String == GatorProtocol.suite,
                  let keyId = data["keyId"] as? String, !keyId.isEmpty,
                  let publicKey = offer["keyForAuth"] as? String,
                  let usuario, let passphrase else {
                throw GatorProtocol.ProtocolError.unsupportedSuite
            }

            let authentication = try JSONSerialization.data(withJSONObject: [
                "type": "authenticateme",
                "message": passphrase,
                "data": ["usuario": usuario]
            ])
            let session = try GatorProtocol.start(
                keyId: keyId,
                publicKey: publicKey,
                authentication: authentication
            )
            self.session = session
            self.usuario = nil
            self.passphrase = nil
            transition(to: .authenticating)
            transport?.send(text: session.initialEnvelope) { [weak self] error in
                guard let error, let self else { return }
                self.queue.async { self.fail(error) }
            }
            return
        }

        let message = try Self.jsonObject(try session!.open(text))
        switch message["type"] as? String {
        case "authsuccess":
            transition(to: .authenticated)
            startKeepAlive()
        case "forcedclosure":
            throw ClientError.connectionRejected(message["estatusDesc"] as? String ?? "Connection rejected")
        case "event":
            let message = UncheckedSendable(message)
            callbackQueue.async { [weak self] in self?.onEvent(message.value) }
        default:
            let message = UncheckedSendable(message)
            callbackQueue.async { [weak self] in self?.onMessage(message.value) }
        }
    }

    private func startKeepAlive() {
        guard keepAliveInterval > 0 else { return }
        keepAliveTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + keepAliveInterval, repeating: keepAliveInterval)
        timer.setEventHandler { [weak self] in
            guard let self, let transport = self.transport else { return }
            transport.sendPing { [weak self] error in
                guard let error, let self else { return }
                self.queue.async { self.fail(error) }
            }
        }
        keepAliveTimer = timer
        timer.resume()
    }

    private func fail(_ error: Error) {
        guard transport != nil else { return }
        report(error)
        transport?.cancel(code: .protocolError, reason: nil)
        reset()
    }

    private func report(_ error: Error) {
        callbackQueue.async { [weak self] in self?.onError(error) }
    }

    private func transition(to state: State) {
        stateLock.lock()
        let changed = storedState != state
        storedState = state
        stateLock.unlock()
        guard changed else { return }
        callbackQueue.async { [weak self] in self?.onState(state.rawValue) }
    }

    private func reset() {
        keepAliveTimer?.cancel()
        keepAliveTimer = nil
        session = nil
        transport = nil
        usuario = nil
        passphrase = nil
        transition(to: .closed)
    }

    private func complete(
        _ completion: @escaping (Result<Void, Error>) -> Void,
        with result: Result<Void, Error>
    ) {
        let completion = UncheckedSendable(completion)
        callbackQueue.async { completion.value(result) }
    }

    private static func jsonString(_ value: [String: Any]) throws -> String {
        guard JSONSerialization.isValidJSONObject(value) else { throw ClientError.invalidJSON }
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

    private static func closeReason(_ reason: String) -> Data? {
        guard !reason.isEmpty else { return nil }
        var result = ""
        for character in reason {
            let candidate = result + String(character)
            guard candidate.utf8.count <= 123 else { break }
            result = candidate
        }
        return Data(result.utf8)
    }
}

extension GatorWebSocketClient.ClientError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .alreadyConnected: "Client is already connected"
        case .notAuthenticated: "Client is not authenticated"
        case .textMessageRequired: "Server must send text WebSocket messages"
        case .invalidJSON: "Message is not a valid JSON object"
        case .invalidURL: "WebSocket URL must use ws:// or wss://"
        case .connectionRejected(let reason): reason
        }
    }
}

protocol GatorWebSocketTransport: AnyObject, Sendable {
    func resume()
    func send(text: String, completion: @escaping @Sendable (Error?) -> Void)
    func receive(completion: @escaping @Sendable (Result<String, Error>) -> Void)
    func sendPing(completion: @escaping @Sendable (Error?) -> Void)
    func cancel(code: URLSessionWebSocketTask.CloseCode, reason: Data?)
}

private final class URLSessionWebSocketTransport: GatorWebSocketTransport, @unchecked Sendable {
    private let session: URLSession
    private let task: URLSessionWebSocketTask

    init(url: URL, configuration: URLSessionConfiguration) {
        let session = URLSession(configuration: configuration)
        self.session = session
        task = session.webSocketTask(with: url)
    }

    func resume() {
        task.resume()
    }

    func send(text: String, completion: @escaping @Sendable (Error?) -> Void) {
        task.send(.string(text), completionHandler: completion)
    }

    func receive(completion: @escaping @Sendable (Result<String, Error>) -> Void) {
        task.receive { result in
            switch result {
            case .success(.string(let text)):
                completion(.success(text))
            case .success:
                completion(.failure(GatorWebSocketClient.ClientError.textMessageRequired))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    func sendPing(completion: @escaping @Sendable (Error?) -> Void) {
        task.sendPing(pongReceiveHandler: completion)
    }

    func cancel(code: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        task.cancel(with: code, reason: reason)
        session.invalidateAndCancel()
    }
}

private struct UncheckedSendable<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}
