package gator.websocket.client;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

final class GatorProtocol {
    static final String SUITE = "DHKEM_X25519_HKDF_SHA256_AES_256_GCM";
    private static final byte[] INFO = bytes("gator-websockets-v1");
    private static final Gson GSON = new Gson();

    private GatorProtocol() {}

    static Session start(String keyId, String publicKey, String authentication) throws GeneralSecurityException {
        X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(new SecureRandom());
        return start(keyId, decode(publicKey), authentication, ephemeral);
    }

    static Session start(String keyId, byte[] publicKey, String authentication, X25519PrivateKeyParameters ephemeral)
            throws GeneralSecurityException {
        Setup setup = setup(publicKey, ephemeral);
        Context context = context(setup.sharedSecret, INFO);
        byte[] initial = seal(context.key, context.baseNonce, 0, bytes("gator-ws-v1|" + keyId + "|hpke|0"), bytes(authentication));
        byte[] clientMaterial = export(context, "gator-ws-v1/client-to-server", 44);
        byte[] serverMaterial = export(context, "gator-ws-v1/server-to-client", 44);
        Envelope envelope = new Envelope(1, keyId, encode(setup.encapsulation), 0, encode(initial));
        return new Session(keyId, GSON.toJson(envelope), new CipherState(clientMaterial), new CipherState(serverMaterial));
    }

    static Setup setup(byte[] recipientPublicKey, X25519PrivateKeyParameters ephemeral) throws GeneralSecurityException {
        if (recipientPublicKey.length != 32) throw new GeneralSecurityException("Invalid X25519 public key");
        X25519PublicKeyParameters recipient = new X25519PublicKeyParameters(recipientPublicKey, 0);
        byte[] dh = new byte[32];
        ephemeral.generateSecret(recipient, dh, 0);
        byte[] encapsulation = ephemeral.generatePublicKey().getEncoded();
        byte[] kemSuiteId = concat(bytes("KEM"), i2osp(0x0020));
        byte[] eaePrk = labeledExtract(kemSuiteId, new byte[0], "eae_prk", dh);
        byte[] sharedSecret = labeledExpand(kemSuiteId, eaePrk, "shared_secret",
                concat(encapsulation, recipientPublicKey), 32);
        return new Setup(encapsulation, sharedSecret);
    }

    static Context context(byte[] sharedSecret, byte[] info) throws GeneralSecurityException {
        byte[] suiteId = concat(bytes("HPKE"), i2osp(0x0020), i2osp(0x0001), i2osp(0x0002));
        byte[] pskIdHash = labeledExtract(suiteId, new byte[0], "psk_id_hash", new byte[0]);
        byte[] infoHash = labeledExtract(suiteId, new byte[0], "info_hash", info);
        byte[] scheduleContext = concat(new byte[] {0}, pskIdHash, infoHash);
        byte[] secret = labeledExtract(suiteId, sharedSecret, "secret", new byte[0]);
        return new Context(
                labeledExpand(suiteId, secret, "key", scheduleContext, 32),
                labeledExpand(suiteId, secret, "base_nonce", scheduleContext, 12),
                labeledExpand(suiteId, secret, "exp", scheduleContext, 32), suiteId);
    }

    static byte[] export(Context context, String exporterContext, int length) throws GeneralSecurityException {
        return labeledExpand(context.suiteId, context.exporterSecret, "sec", bytes(exporterContext), length);
    }

    static byte[] seal(byte[] key, byte[] baseNonce, long sequence, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(baseNonce, sequence)));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    static byte[] open(byte[] key, byte[] baseNonce, long sequence, byte[] aad, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce(baseNonce, sequence)));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] labeledExtract(byte[] suiteId, byte[] salt, String label, byte[] ikm)
            throws GeneralSecurityException {
        return extract(salt, concat(bytes("HPKE-v1"), suiteId, bytes(label), ikm));
    }

    private static byte[] labeledExpand(byte[] suiteId, byte[] prk, String label, byte[] info, int length)
            throws GeneralSecurityException {
        return expand(prk, concat(ByteBuffer.allocate(2).putShort((short) length).array(),
                bytes("HPKE-v1"), suiteId, bytes(label), info), length);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length > 255 * 32) throw new GeneralSecurityException("HKDF output is too long");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] previous = new byte[0];
        for (int counter = 1; output.size() < length; counter++) {
            mac.reset();
            mac.update(previous);
            mac.update(info);
            previous = mac.doFinal(new byte[] {(byte) counter});
            output.write(previous, 0, previous.length);
        }
        return Arrays.copyOf(output.toByteArray(), length);
    }

    private static byte[] nonce(byte[] baseNonce, long sequence) {
        byte[] nonce = baseNonce.clone();
        for (int index = 0; index < Long.BYTES; index++) {
            nonce[nonce.length - 1 - index] ^= (byte) (sequence >>> (8 * index));
        }
        return nonce;
    }

    private static byte[] i2osp(int value) {
        return new byte[] {(byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... values) {
        int size = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] value : values) buffer.put(value);
        return buffer.array();
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] decode(String value) throws GeneralSecurityException {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("Invalid Base64URL value", error);
        }
    }

    static final class Session {
        final String initialEnvelope;
        private final String keyId;
        private final CipherState outbound;
        private final CipherState inbound;

        Session(String keyId, String initialEnvelope, CipherState outbound, CipherState inbound) {
            this.keyId = keyId;
            this.initialEnvelope = initialEnvelope;
            this.outbound = outbound;
            this.inbound = inbound;
        }

        synchronized String seal(String message) throws GeneralSecurityException {
            long sequence = outbound.sequence;
            byte[] ciphertext = outbound.seal(bytes("gator-ws-v1|" + keyId + "|client-to-server|" + sequence), bytes(message));
            return GSON.toJson(new Envelope(1, keyId, null, sequence, encode(ciphertext)));
        }

        synchronized String open(String json) throws GeneralSecurityException {
            Envelope envelope;
            try {
                envelope = GSON.fromJson(json, Envelope.class);
            } catch (RuntimeException error) {
                throw new GeneralSecurityException("Malformed encrypted envelope", error);
            }
            if (envelope == null || envelope.version != 1 || !keyId.equals(envelope.keyId)
                    || envelope.encapsulation != null || envelope.sequence < 0 || envelope.ciphertext == null) {
                throw new GeneralSecurityException("Invalid encrypted envelope");
            }
            return new String(inbound.open(envelope.sequence,
                    bytes("gator-ws-v1|" + keyId + "|server-to-client|" + envelope.sequence),
                    decode(envelope.ciphertext)), StandardCharsets.UTF_8);
        }
    }

    static final class CipherState {
        private final byte[] key;
        private final byte[] baseNonce;
        private long sequence;

        CipherState(byte[] material) throws GeneralSecurityException {
            if (material.length != 44) throw new GeneralSecurityException("Invalid session material");
            key = Arrays.copyOf(material, 32);
            baseNonce = Arrays.copyOfRange(material, 32, 44);
        }

        byte[] seal(byte[] aad, byte[] plaintext) throws GeneralSecurityException {
            if (sequence == Long.MAX_VALUE) throw new GeneralSecurityException("Encrypted message sequence exhausted");
            byte[] ciphertext = GatorProtocol.seal(key, baseNonce, sequence, aad, plaintext);
            sequence++;
            return ciphertext;
        }

        byte[] open(long receivedSequence, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
            if (sequence == Long.MAX_VALUE || receivedSequence != sequence) {
                throw new GeneralSecurityException("Unexpected encrypted message sequence");
            }
            byte[] plaintext = GatorProtocol.open(key, baseNonce, sequence, aad, ciphertext);
            sequence++;
            return plaintext;
        }
    }

    static final class Setup {
        final byte[] encapsulation;
        final byte[] sharedSecret;

        Setup(byte[] encapsulation, byte[] sharedSecret) {
            this.encapsulation = encapsulation;
            this.sharedSecret = sharedSecret;
        }
    }

    static final class Context {
        final byte[] key;
        final byte[] baseNonce;
        final byte[] exporterSecret;
        final byte[] suiteId;

        Context(byte[] key, byte[] baseNonce, byte[] exporterSecret, byte[] suiteId) {
            this.key = key;
            this.baseNonce = baseNonce;
            this.exporterSecret = exporterSecret;
            this.suiteId = suiteId;
        }
    }

    private static final class Envelope {
        final int version;
        final String keyId;
        final String encapsulation;
        final long sequence;
        final String ciphertext;

        Envelope(int version, String keyId, String encapsulation, long sequence, String ciphertext) {
            this.version = version;
            this.keyId = keyId;
            this.encapsulation = encapsulation;
            this.sequence = sequence;
            this.ciphertext = ciphertext;
        }
    }
}
