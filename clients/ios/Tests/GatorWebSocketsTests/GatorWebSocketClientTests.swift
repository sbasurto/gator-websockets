import CryptoKit
import XCTest
@testable import GatorWebSockets

final class GatorWebSocketClientTests: XCTestCase {
    func testAuthenticatesAndSendsEncryptedMessage() throws {
        let transport = FakeWebSocketTransport()
        let authenticated = expectation(description: "authenticated")
        let initialSent = expectation(description: "initial authentication sent")
        let messageSent = expectation(description: "application message sent")
        let sendCompleted = expectation(description: "send completion")
        let recipient = Curve25519.KeyAgreement.PrivateKey()
        let publicKey = recipient.publicKey.rawRepresentation
        let client = GatorWebSocketClient(
            url: URL(string: "wss://example.com")!,
            callbackQueue: DispatchQueue(label: "gator.websocket.tests.callbacks"),
            keepAliveInterval: 0,
            transportFactory: { transport }
        )

        var serverInbound: GatorProtocol.CipherState?
        var sendCount = 0
        transport.onSend = { text in
            sendCount += 1
            if sendCount == 1 {
                do {
                    let initial = try JSONDecoder().decode(
                        GatorProtocol.Envelope.self,
                        from: Data(text.utf8)
                    )
                    let encapsulation = try GatorProtocol.decode(initial.encapsulation!)
                    let ephemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: encapsulation)
                    let dh = try recipient.sharedSecretFromKeyAgreement(with: ephemeral)
                        .withUnsafeBytes { Data($0) }
                    let sharedSecret = GatorProtocol.kemSharedSecret(
                        dh: dh,
                        encapsulation: encapsulation,
                        recipientPublicKey: publicKey
                    )
                    let context = GatorProtocol.context(
                        sharedSecret: sharedSecret,
                        info: Data("gator-websockets-v1".utf8)
                    )
                    let authentication = try GatorProtocol.open(
                        key: context.key,
                        baseNonce: context.baseNonce,
                        sequence: 0,
                        aad: Data("gator-ws-v1|ios-test|hpke|0".utf8),
                        ciphertext: GatorProtocol.decode(initial.ciphertext)
                    )
                    let object = try JSONSerialization.jsonObject(with: authentication) as? [String: Any]
                    XCTAssertEqual(object?["type"] as? String, "authenticateme")
                    XCTAssertEqual(object?["message"] as? String, "access-token")
                    XCTAssertNil(object?["data"])

                    serverInbound = try GatorProtocol.CipherState(material: GatorProtocol.export(
                        context: context,
                        exporterContext: "gator-ws-v1/client-to-server",
                        length: 44
                    ))
                    let outbound = try GatorProtocol.CipherState(material: GatorProtocol.export(
                        context: context,
                        exporterContext: "gator-ws-v1/server-to-client",
                        length: 44
                    ))
                    let ciphertext = try outbound.seal(
                        aad: Data("gator-ws-v1|ios-test|server-to-client|0".utf8),
                        plaintext: Data(#"{"type":"authsuccess"}"#.utf8)
                    )
                    let response = GatorProtocol.Envelope(
                        version: 1,
                        keyId: "ios-test",
                        encapsulation: nil,
                        sequence: 0,
                        ciphertext: GatorProtocol.encode(ciphertext)
                    )
                    transport.enqueue(.success(String(
                        data: try JSONEncoder().encode(response),
                        encoding: .utf8
                    )!))
                    initialSent.fulfill()
                } catch {
                    XCTFail("Cannot emulate server authentication: \(error)")
                }
            } else {
                messageSent.fulfill()
            }
        }
        client.onState = { state in
            if state == "authenticated" { authenticated.fulfill() }
        }

        let offer: [String: Any] = [
            "type": "askauth",
            "keyForAuth": GatorProtocol.encode(publicKey),
            "data": [
                "version": "1",
                "keyId": "ios-test",
                "suite": GatorProtocol.suite
            ]
        ]
        transport.enqueue(.success(try json(offer)))
        client.connect(accessToken: "access-token")
        wait(for: [initialSent, authenticated], timeout: 2)

        client.send(["type": "getuserlist"]) { result in
            if case .failure(let error) = result { XCTFail("Send failed: \(error)") }
            sendCompleted.fulfill()
        }
        wait(for: [messageSent, sendCompleted], timeout: 2)

        let request = try JSONDecoder().decode(
            GatorProtocol.Envelope.self,
            from: Data(transport.sentTexts[1].utf8)
        )
        let plaintext = try serverInbound!.open(
            receivedSequence: 0,
            aad: Data("gator-ws-v1|ios-test|client-to-server|0".utf8),
            ciphertext: GatorProtocol.decode(request.ciphertext)
        )
        XCTAssertEqual(String(data: plaintext, encoding: .utf8), #"{"type":"getuserlist"}"#)
        XCTAssertTrue(client.authenticated)
    }

    func testMalformedOfferClosesConnection() {
        let transport = FakeWebSocketTransport()
        let failed = expectation(description: "error callback")
        let closed = expectation(description: "closed state")
        let client = GatorWebSocketClient(
            url: URL(string: "wss://example.com")!,
            callbackQueue: DispatchQueue(label: "gator.websocket.tests.callbacks"),
            keepAliveInterval: 0,
            transportFactory: { transport }
        )
        client.onError = { _ in failed.fulfill() }
        client.onState = { if $0 == "closed" { closed.fulfill() } }

        transport.enqueue(.success("{}"))
        client.connect(accessToken: "access-token")

        wait(for: [failed, closed], timeout: 2)
        XCTAssertEqual(transport.cancelledCode, .protocolError)
        XCTAssertFalse(client.authenticated)
    }

    func testInvalidURLDoesNotCreateTransport() {
        let failed = expectation(description: "invalid URL")
        let closed = expectation(description: "closed state")
        let created = LockedFlag()
        let client = GatorWebSocketClient(
            url: URL(string: "https://example.com")!,
            callbackQueue: DispatchQueue(label: "gator.websocket.tests.callbacks"),
            keepAliveInterval: 0,
            transportFactory: {
                created.set()
                return FakeWebSocketTransport()
            }
        )
        client.onError = { error in
            guard case GatorWebSocketClient.ClientError.invalidURL = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            failed.fulfill()
        }
        client.onState = { if $0 == "closed" { closed.fulfill() } }

        client.connect(accessToken: "access-token")

        wait(for: [failed, closed], timeout: 2)
        XCTAssertFalse(created.value)
    }

    private func json(_ object: [String: Any]) throws -> String {
        String(data: try JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }
}

private final class LockedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var storedValue = false

    var value: Bool {
        lock.lock()
        defer { lock.unlock() }
        return storedValue
    }

    func set() {
        lock.lock()
        storedValue = true
        lock.unlock()
    }
}

private final class FakeWebSocketTransport: GatorWebSocketTransport, @unchecked Sendable {
    var onSend: ((String) -> Void)?
    private(set) var sentTexts: [String] = []
    private(set) var cancelledCode: URLSessionWebSocketTask.CloseCode?

    private let lock = NSLock()
    private var pending: [Result<String, Error>] = []
    private var receiver: ((Result<String, Error>) -> Void)?

    func resume() {}

    func send(text: String, completion: @escaping @Sendable (Error?) -> Void) {
        lock.lock()
        sentTexts.append(text)
        let callback = onSend
        lock.unlock()
        callback?(text)
        completion(nil)
    }

    func receive(completion: @escaping @Sendable (Result<String, Error>) -> Void) {
        lock.lock()
        if pending.isEmpty {
            receiver = completion
            lock.unlock()
        } else {
            let result = pending.removeFirst()
            lock.unlock()
            completion(result)
        }
    }

    func sendPing(completion: @escaping @Sendable (Error?) -> Void) {
        completion(nil)
    }

    func cancel(code: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        lock.lock()
        cancelledCode = code
        receiver = nil
        lock.unlock()
    }

    func enqueue(_ result: Result<String, Error>) {
        lock.lock()
        if let receiver {
            self.receiver = nil
            lock.unlock()
            receiver(result)
        } else {
            pending.append(result)
            lock.unlock()
        }
    }
}
