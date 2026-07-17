import CryptoKit
import Foundation

enum GatorProtocol {
    static let suite = "DHKEM_X25519_HKDF_SHA256_AES_256_GCM"
    private static let info = Data("gator-websockets-v1".utf8)

    static func start(keyId: String, publicKey: String, authentication: Data) throws -> Session {
        try start(keyId: keyId, publicKey: decode(publicKey), authentication: authentication,
                  ephemeral: Curve25519.KeyAgreement.PrivateKey())
    }

    static func start(keyId: String, publicKey: Data, authentication: Data,
                      ephemeral: Curve25519.KeyAgreement.PrivateKey) throws -> Session {
        let setup = try setup(recipientPublicKey: publicKey, ephemeral: ephemeral)
        let context = context(sharedSecret: setup.sharedSecret, info: info)
        let initial = try seal(key: context.key, baseNonce: context.baseNonce, sequence: 0,
                               aad: Data("gator-ws-v1|\(keyId)|hpke|0".utf8), plaintext: authentication)
        let clientMaterial = export(context: context, exporterContext: "gator-ws-v1/client-to-server", length: 44)
        let serverMaterial = export(context: context, exporterContext: "gator-ws-v1/server-to-client", length: 44)
        let envelope = Envelope(version: 1, keyId: keyId, encapsulation: encode(setup.encapsulation),
                                sequence: 0, ciphertext: encode(initial))
        return try Session(keyId: keyId, initialEnvelope: encodeJSON(envelope),
                           outbound: CipherState(material: clientMaterial),
                           inbound: CipherState(material: serverMaterial))
    }

    static func setup(recipientPublicKey: Data, ephemeral: Curve25519.KeyAgreement.PrivateKey) throws -> Setup {
        guard recipientPublicKey.count == 32 else { throw ProtocolError.invalidPublicKey }
        let recipient = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPublicKey)
        let dh = try ephemeral.sharedSecretFromKeyAgreement(with: recipient).withUnsafeBytes { Data($0) }
        let encapsulation = ephemeral.publicKey.rawRepresentation
        return Setup(encapsulation: encapsulation,
                     sharedSecret: kemSharedSecret(dh: dh, encapsulation: encapsulation,
                                                   recipientPublicKey: recipientPublicKey))
    }

    static func kemSharedSecret(dh: Data, encapsulation: Data, recipientPublicKey: Data) -> Data {
        let suiteId = Data("KEM".utf8) + i2osp(0x0020)
        let eaePrk = labeledExtract(suiteId: suiteId, salt: Data(), label: "eae_prk", ikm: dh)
        return labeledExpand(suiteId: suiteId, prk: eaePrk, label: "shared_secret",
                             info: encapsulation + recipientPublicKey, length: 32)
    }

    static func context(sharedSecret: Data, info: Data) -> Context {
        let suiteId = Data("HPKE".utf8) + i2osp(0x0020) + i2osp(0x0001) + i2osp(0x0002)
        let pskIdHash = labeledExtract(suiteId: suiteId, salt: Data(), label: "psk_id_hash", ikm: Data())
        let infoHash = labeledExtract(suiteId: suiteId, salt: Data(), label: "info_hash", ikm: info)
        let scheduleContext = Data([0]) + pskIdHash + infoHash
        let secret = labeledExtract(suiteId: suiteId, salt: sharedSecret, label: "secret", ikm: Data())
        return Context(
            key: labeledExpand(suiteId: suiteId, prk: secret, label: "key", info: scheduleContext, length: 32),
            baseNonce: labeledExpand(suiteId: suiteId, prk: secret, label: "base_nonce", info: scheduleContext, length: 12),
            exporterSecret: labeledExpand(suiteId: suiteId, prk: secret, label: "exp", info: scheduleContext, length: 32),
            suiteId: suiteId
        )
    }

    static func export(context: Context, exporterContext: String, length: Int) -> Data {
        labeledExpand(suiteId: context.suiteId, prk: context.exporterSecret, label: "sec",
                      info: Data(exporterContext.utf8), length: length)
    }

    static func seal(key: Data, baseNonce: Data, sequence: UInt64, aad: Data, plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                   nonce: AES.GCM.Nonce(data: nonce(baseNonce, sequence)), authenticating: aad)
        return box.ciphertext + box.tag
    }

    static func open(key: Data, baseNonce: Data, sequence: UInt64, aad: Data, ciphertext: Data) throws -> Data {
        guard ciphertext.count >= 16 else { throw ProtocolError.invalidEnvelope }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce(baseNonce, sequence)),
                                        ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    private static func labeledExtract(suiteId: Data, salt: Data, label: String, ikm: Data) -> Data {
        extract(salt: salt, ikm: Data("HPKE-v1".utf8) + suiteId + Data(label.utf8) + ikm)
    }

    private static func labeledExpand(suiteId: Data, prk: Data, label: String, info: Data, length: Int) -> Data {
        expand(prk: prk, info: i2osp(length) + Data("HPKE-v1".utf8) + suiteId + Data(label.utf8) + info,
               length: length)
    }

    private static func extract(salt: Data, ikm: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: ikm,
             using: SymmetricKey(data: salt.isEmpty ? Data(repeating: 0, count: 32) : salt)))
    }

    private static func expand(prk: Data, info: Data, length: Int) -> Data {
        precondition(length <= 255 * 32)
        var output = Data()
        var previous = Data()
        var counter: UInt8 = 1
        while output.count < length {
            previous = Data(HMAC<SHA256>.authenticationCode(for: previous + info + Data([counter]),
                            using: SymmetricKey(data: prk)))
            output += previous
            counter &+= 1
        }
        return output.prefix(length)
    }

    private static func nonce(_ baseNonce: Data, _ sequence: UInt64) -> Data {
        var nonce = [UInt8](baseNonce)
        var counter = sequence
        for index in 0..<8 {
            nonce[nonce.count - 1 - index] ^= UInt8(counter & 0xff)
            counter >>= 8
        }
        return Data(nonce)
    }

    private static func i2osp(_ value: Int) -> Data {
        Data([UInt8((value >> 8) & 0xff), UInt8(value & 0xff)])
    }

    static func encode(_ value: Data) -> String {
        value.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ value: String) throws -> Data {
        let standard = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let padded = standard.padding(toLength: ((standard.count + 3) / 4) * 4, withPad: "=", startingAt: 0)
        guard let data = Data(base64Encoded: padded) else { throw ProtocolError.invalidBase64 }
        return data
    }

    private static func encodeJSON<T: Encodable>(_ value: T) throws -> String {
        guard let string = String(data: try JSONEncoder().encode(value), encoding: .utf8) else {
            throw ProtocolError.invalidEnvelope
        }
        return string
    }

    final class Session {
        let initialEnvelope: String
        private let keyId: String
        private let outbound: CipherState
        private let inbound: CipherState

        init(keyId: String, initialEnvelope: String, outbound: CipherState, inbound: CipherState) {
            self.keyId = keyId
            self.initialEnvelope = initialEnvelope
            self.outbound = outbound
            self.inbound = inbound
        }

        func seal(_ message: String) throws -> String {
            let sequence = outbound.sequence
            let ciphertext = try outbound.seal(aad: Data("gator-ws-v1|\(keyId)|client-to-server|\(sequence)".utf8),
                                               plaintext: Data(message.utf8))
            return try encodeJSON(Envelope(version: 1, keyId: keyId, encapsulation: nil,
                                           sequence: sequence, ciphertext: encode(ciphertext)))
        }

        func open(_ json: String) throws -> String {
            guard let data = json.data(using: .utf8) else { throw ProtocolError.invalidEnvelope }
            let envelope = try JSONDecoder().decode(Envelope.self, from: data)
            guard envelope.version == 1, envelope.keyId == keyId, envelope.encapsulation == nil else {
                throw ProtocolError.invalidEnvelope
            }
            let plaintext = try inbound.open(receivedSequence: envelope.sequence,
                aad: Data("gator-ws-v1|\(keyId)|server-to-client|\(envelope.sequence)".utf8),
                ciphertext: decode(envelope.ciphertext))
            guard let message = String(data: plaintext, encoding: .utf8) else { throw ProtocolError.invalidEnvelope }
            return message
        }
    }

    final class CipherState {
        private let key: Data
        private let baseNonce: Data
        private(set) var sequence: UInt64 = 0

        init(material: Data) throws {
            guard material.count == 44 else { throw ProtocolError.invalidSessionMaterial }
            key = material.prefix(32)
            baseNonce = material.suffix(12)
        }

        func seal(aad: Data, plaintext: Data) throws -> Data {
            guard sequence < UInt64.max else { throw ProtocolError.sequenceExhausted }
            let ciphertext = try GatorProtocol.seal(key: key, baseNonce: baseNonce,
                                                     sequence: sequence, aad: aad, plaintext: plaintext)
            sequence += 1
            return ciphertext
        }

        func open(receivedSequence: UInt64, aad: Data, ciphertext: Data) throws -> Data {
            guard sequence < UInt64.max, receivedSequence == sequence else { throw ProtocolError.unexpectedSequence }
            let plaintext = try GatorProtocol.open(key: key, baseNonce: baseNonce,
                                                   sequence: sequence, aad: aad, ciphertext: ciphertext)
            sequence += 1
            return plaintext
        }
    }

    struct Setup { let encapsulation: Data; let sharedSecret: Data }
    struct Context { let key: Data; let baseNonce: Data; let exporterSecret: Data; let suiteId: Data }
    struct Envelope: Codable {
        let version: Int
        let keyId: String
        let encapsulation: String?
        let sequence: UInt64
        let ciphertext: String
    }

    enum ProtocolError: Error {
        case invalidPublicKey, invalidBase64, invalidEnvelope, invalidSessionMaterial
        case unexpectedSequence, sequenceExhausted, unsupportedSuite
    }
}
