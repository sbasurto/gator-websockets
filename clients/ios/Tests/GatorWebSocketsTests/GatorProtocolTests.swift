import CryptoKit
import XCTest
@testable import GatorWebSockets

final class GatorProtocolTests: XCTestCase {
    func testHPKEAndBidirectionalSession() throws {
        let recipientPrivate = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(hex:
            "4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8"))
        let recipientPublic = Data(hex: "3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d")
        let ephemeral = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(hex:
            "52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736"))
        let setup = try GatorProtocol.setup(recipientPublicKey: recipientPublic, ephemeral: ephemeral)
        XCTAssertEqual(setup.encapsulation, Data(hex:
            "37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431"))
        XCTAssertEqual(setup.sharedSecret, Data(hex:
            "fe0e18c9f024ce43799ae393c7e8fe8fce9d218875e8227b0187c04e7d2ea1fc"))

        let authentication = Data("{\"type\":\"authenticateme\"}".utf8)
        let client = try GatorProtocol.start(keyId: "test-key", publicKey: recipientPublic,
                                             authentication: authentication, ephemeral: ephemeral)
        let initial = try JSONDecoder().decode(GatorProtocol.Envelope.self, from: Data(client.initialEnvelope.utf8))
        let ephemeralPublic = try Curve25519.KeyAgreement.PublicKey(rawRepresentation:
            GatorProtocol.decode(initial.encapsulation!))
        let dh = try recipientPrivate.sharedSecretFromKeyAgreement(with: ephemeralPublic).withUnsafeBytes { Data($0) }
        let sharedSecret = GatorProtocol.kemSharedSecret(dh: dh, encapsulation: setup.encapsulation,
                                                         recipientPublicKey: recipientPublic)
        let context = GatorProtocol.context(sharedSecret: sharedSecret, info: Data("gator-websockets-v1".utf8))
        let opened = try GatorProtocol.open(key: context.key, baseNonce: context.baseNonce, sequence: 0,
            aad: Data("gator-ws-v1|test-key|hpke|0".utf8), ciphertext: GatorProtocol.decode(initial.ciphertext))
        XCTAssertEqual(opened, authentication)

        let serverInbound = try GatorProtocol.CipherState(material:
            GatorProtocol.export(context: context, exporterContext: "gator-ws-v1/client-to-server", length: 44))
        let request = try JSONDecoder().decode(GatorProtocol.Envelope.self,
            from: Data(try client.seal("{\"type\":\"getuserlist\"}").utf8))
        let requestPlaintext = try serverInbound.open(receivedSequence: 0,
            aad: Data("gator-ws-v1|test-key|client-to-server|0".utf8),
            ciphertext: GatorProtocol.decode(request.ciphertext))
        XCTAssertEqual(String(data: requestPlaintext, encoding: .utf8), "{\"type\":\"getuserlist\"}")

        let serverOutbound = try GatorProtocol.CipherState(material:
            GatorProtocol.export(context: context, exporterContext: "gator-ws-v1/server-to-client", length: 44))
        let responseCiphertext = try serverOutbound.seal(aad: Data("gator-ws-v1|test-key|server-to-client|0".utf8),
                                                          plaintext: Data("{\"type\":\"userslist\"}".utf8))
        let response = GatorProtocol.Envelope(version: 1, keyId: "test-key", encapsulation: nil,
                                               sequence: 0, ciphertext: GatorProtocol.encode(responseCiphertext))
        let responseJSON = String(data: try JSONEncoder().encode(response), encoding: .utf8)!
        XCTAssertEqual(try client.open(responseJSON), "{\"type\":\"userslist\"}")
        XCTAssertThrowsError(try client.open(responseJSON))
    }
}

private extension Data {
    init(hex: String) {
        self.init(stride(from: 0, to: hex.count, by: 2).map { index in
            let start = hex.index(hex.startIndex, offsetBy: index)
            return UInt8(String(hex[start..<hex.index(start, offsetBy: 2)]), radix: 16)!
        })
    }
}
