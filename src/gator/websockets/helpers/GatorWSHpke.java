/*
 * Copyright (C) 2021 Sergio Basurto Juárez
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package gator.websockets.helpers;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RFC 9180 base-mode recipient and bidirectional session exporter. */
public final class GatorWSHpke {
        public static final int VERSION = 1;
        public static final String SUITE = "DHKEM_X25519_HKDF_SHA256_AES_256_GCM";
        private static final int KEM_ID = 0x0020;
        private static final int KDF_ID = 0x0001;
        private static final int AEAD_ID = 0x0002;
        private static final int HASH_SIZE = 32;
        private static final int KEY_SIZE = 32;
        private static final int NONCE_SIZE = 12;
        private static final byte[] INFO = "gator-websockets-v1".getBytes(StandardCharsets.UTF_8);
        private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
        private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
        private static final Gson GSON = new Gson();

        private final GatorWSKeyManager.Generation generation;
        private CipherState inbound;
        private CipherState outbound;

        public GatorWSHpke(GatorWSKeyManager.Generation generation) {
                this.generation = generation;
        }

        public String keyId() {
                return generation.id();
        }

        public String publicKey() {
                XECPublicKey key = (XECPublicKey) generation.keyPair().getPublic();
                byte[] bigEndian = key.getU().toByteArray();
                byte[] littleEndian = new byte[32];
                for(int i = 0; i < littleEndian.length && i < bigEndian.length; i++) {
                        littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
                }
                return encode(littleEndian);
        }

        public boolean isEstablished() {
                return inbound != null;
        }

        public String open(String encodedEnvelope) throws GeneralSecurityException {
                Envelope envelope = parse(encodedEnvelope);
                validate(envelope, isEstablished());
                if(!isEstablished()) {
                        Context context = context(decapsulate(generation.keyPair(), decode(envelope.encapsulation())), AEAD_ID, KEY_SIZE, INFO);
                        byte[] clientMaterial = export(context, "gator-ws-v1/client-to-server", KEY_SIZE + NONCE_SIZE);
                        byte[] serverMaterial = export(context, "gator-ws-v1/server-to-client", KEY_SIZE + NONCE_SIZE);
                        String plaintext = new String(open(context.key(), context.baseNonce(), envelope.sequence(),
                                aad(envelope, "hpke"), decode(envelope.ciphertext())), StandardCharsets.UTF_8);
                        inbound = state(clientMaterial);
                        outbound = state(serverMaterial);
                        return plaintext;
                }
                return inbound.open(envelope.sequence(), aad(envelope, "client-to-server"), decode(envelope.ciphertext()));
        }

        public String seal(String plaintext) throws GeneralSecurityException {
                if(!isEstablished()) {
                        throw new GeneralSecurityException("HPKE session is not established");
                }
                long sequence = outbound.sequence();
                Envelope envelope = new Envelope(VERSION, keyId(), null, sequence, "");
                byte[] ciphertext = outbound.seal(aad(envelope, "server-to-client"), plaintext.getBytes(StandardCharsets.UTF_8));
                return GSON.toJson(new Envelope(VERSION, keyId(), null, sequence, encode(ciphertext)));
        }

        public static boolean looksLikeEnvelope(String json) {
                try {
                        Envelope envelope = GSON.fromJson(json, Envelope.class);
                        return envelope != null && envelope.ciphertext() != null;
                } catch(Exception ignored) {
                        return false;
                }
        }

        private void validate(Envelope envelope, boolean established) throws GeneralSecurityException {
                if(envelope.version() != VERSION || !keyId().equals(envelope.keyId())
                        || envelope.sequence() < 0 || envelope.ciphertext() == null) {
                        throw new GeneralSecurityException("Invalid encrypted envelope");
                }
                if(!established && (envelope.sequence() != 0 || envelope.encapsulation() == null)) {
                        throw new GeneralSecurityException("Initial HPKE envelope is incomplete");
                }
                if(established && envelope.encapsulation() != null) {
                        throw new GeneralSecurityException("Unexpected HPKE encapsulation");
                }
        }

        private Envelope parse(String json) throws GeneralSecurityException {
                try {
                        Envelope envelope = GSON.fromJson(json, Envelope.class);
                        if(envelope == null) throw new GeneralSecurityException("Missing encrypted envelope");
                        return envelope;
                } catch(GeneralSecurityException e) {
                        throw e;
                } catch(Exception e) {
                        throw new GeneralSecurityException("Malformed encrypted envelope", e);
                }
        }

        private byte[] aad(Envelope envelope, String direction) {
                return ("gator-ws-v1|" + envelope.keyId() + "|" + direction + "|" + envelope.sequence())
                        .getBytes(StandardCharsets.UTF_8);
        }

        static byte[] decapsulate(KeyPair recipient, byte[] encapsulation) throws GeneralSecurityException {
                if(encapsulation.length != 32) throw new GeneralSecurityException("Invalid X25519 encapsulation");
                return KEM.getInstance("DHKEM").newDecapsulator(recipient.getPrivate())
                        .decapsulate(encapsulation).getEncoded();
        }

        static Context context(byte[] sharedSecret, int aeadId, int keySize, byte[] info) throws GeneralSecurityException {
                byte[] suiteId = concat("HPKE".getBytes(StandardCharsets.US_ASCII), i2osp(KEM_ID), i2osp(KDF_ID), i2osp(aeadId));
                byte[] pskIdHash = labeledExtract(suiteId, new byte[0], "psk_id_hash", new byte[0]);
                byte[] infoHash = labeledExtract(suiteId, new byte[0], "info_hash", info);
                byte[] scheduleContext = concat(new byte[] {0}, pskIdHash, infoHash);
                byte[] secret = labeledExtract(suiteId, sharedSecret, "secret", new byte[0]);
                return new Context(
                        labeledExpand(suiteId, secret, "key", scheduleContext, keySize),
                        labeledExpand(suiteId, secret, "base_nonce", scheduleContext, NONCE_SIZE),
                        labeledExpand(suiteId, secret, "exp", scheduleContext, HASH_SIZE),
                        suiteId);
        }

        static byte[] open(byte[] key, byte[] baseNonce, long sequence, byte[] aad, byte[] ciphertext)
                throws GeneralSecurityException {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce(baseNonce, sequence)));
                cipher.updateAAD(aad);
                return cipher.doFinal(ciphertext);
        }

        static byte[] seal(byte[] key, byte[] baseNonce, long sequence, byte[] aad, byte[] plaintext)
                throws GeneralSecurityException {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce(baseNonce, sequence)));
                cipher.updateAAD(aad);
                return cipher.doFinal(plaintext);
        }

        static byte[] export(Context context, String exporterContext, int length) throws GeneralSecurityException {
                return labeledExpand(context.suiteId(), context.exporterSecret(), "sec",
                        exporterContext.getBytes(StandardCharsets.UTF_8), length);
        }

        private static CipherState state(byte[] material) {
                return new CipherState(Arrays.copyOfRange(material, 0, KEY_SIZE),
                        Arrays.copyOfRange(material, KEY_SIZE, KEY_SIZE + NONCE_SIZE));
        }

        private static byte[] labeledExtract(byte[] suiteId, byte[] salt, String label, byte[] ikm)
                throws GeneralSecurityException {
                return extract(salt, concat("HPKE-v1".getBytes(StandardCharsets.US_ASCII), suiteId,
                        label.getBytes(StandardCharsets.US_ASCII), ikm));
        }

        private static byte[] labeledExpand(byte[] suiteId, byte[] prk, String label, byte[] info, int length)
                throws GeneralSecurityException {
                return expand(prk, concat(ByteBuffer.allocate(2).putShort((short) length).array(),
                        "HPKE-v1".getBytes(StandardCharsets.US_ASCII), suiteId,
                        label.getBytes(StandardCharsets.US_ASCII), info), length);
        }

        private static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(salt.length == 0 ? new byte[HASH_SIZE] : salt, "HmacSHA256"));
                return mac.doFinal(ikm);
        }

        private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
                if(length > 255 * HASH_SIZE) throw new GeneralSecurityException("HKDF output is too long");
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] previous = new byte[0];
                for(int counter = 1; output.size() < length; counter++) {
                        mac.reset();
                        mac.update(previous);
                        mac.update(info);
                        previous = mac.doFinal(new byte[] {(byte) counter});
                        output.writeBytes(previous);
                }
                return Arrays.copyOf(output.toByteArray(), length);
        }

        private static byte[] nonce(byte[] baseNonce, long sequence) {
                byte[] nonce = baseNonce.clone();
                for(int i = 0; i < Long.BYTES; i++) {
                        nonce[nonce.length - 1 - i] ^= (byte) (sequence >>> (8 * i));
                }
                return nonce;
        }

        private static byte[] i2osp(int value) {
                return new byte[] {(byte) (value >>> 8), (byte) value};
        }

        private static byte[] concat(byte[]... values) {
                int size = Arrays.stream(values).mapToInt(value -> value.length).sum();
                ByteBuffer buffer = ByteBuffer.allocate(size);
                for(byte[] value : values) buffer.put(value);
                return buffer.array();
        }

        private static byte[] decode(String value) throws GeneralSecurityException {
                try {
                        return DECODER.decode(value);
                } catch(IllegalArgumentException e) {
                        throw new GeneralSecurityException("Invalid Base64URL value", e);
                }
        }

        private static String encode(byte[] value) {
                return ENCODER.encodeToString(value);
        }

        record Context(byte[] key, byte[] baseNonce, byte[] exporterSecret, byte[] suiteId) {}
        private record Envelope(int version, String keyId, String encapsulation, long sequence, String ciphertext) {}

        private static final class CipherState {
                private final byte[] key;
                private final byte[] baseNonce;
                private long sequence;

                CipherState(byte[] key, byte[] baseNonce) {
                        this.key = key;
                        this.baseNonce = baseNonce;
                }

                long sequence() {
                        return sequence;
                }

                String open(long receivedSequence, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
                        if(sequence == Long.MAX_VALUE) throw new GeneralSecurityException("Encrypted message sequence exhausted");
                        if(receivedSequence != sequence) throw new GeneralSecurityException("Unexpected encrypted message sequence");
                        String plaintext = new String(GatorWSHpke.open(key, baseNonce, sequence, aad, ciphertext), StandardCharsets.UTF_8);
                        sequence++;
                        return plaintext;
                }

                byte[] seal(byte[] aad, byte[] plaintext) throws GeneralSecurityException {
                        if(sequence == Long.MAX_VALUE) throw new GeneralSecurityException("Encrypted message sequence exhausted");
                        byte[] ciphertext = GatorWSHpke.seal(key, baseNonce, sequence, aad, plaintext);
                        sequence++;
                        return ciphertext;
                }
        }
}
