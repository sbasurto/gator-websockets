package gator.websockets.helpers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.KEM;

public final class GatorWSHpkeSelfCheck {
        private static final HexFormat HEX = HexFormat.of();

        private GatorWSHpkeSelfCheck() {}

        public static void run() throws Exception {
                rfc9180BaseVector();
                productionEnvelopeRoundTrip();
                rotatesAtConnectionLimit();
        }

        private static void rfc9180BaseVector() throws Exception {
                byte[] recipientPrivate = hex("4612c550263fc8ad58375df3f557aac531d26850903e55a9f23f21d8534e8ac8");
                byte[] recipientPublic = hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
                byte[] encapsulation = hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431");
                KeyFactory factory = KeyFactory.getInstance("X25519");
                PrivateKey privateKey = factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, recipientPrivate));
                PublicKey publicKey = factory.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519,
                        new BigInteger(1, reverse(recipientPublic))));
                KeyPair recipient = new KeyPair(publicKey, privateKey);
                GatorWSHpke hpke = new GatorWSHpke(new GatorWSKeyManager.Generation("rfc", recipient, Instant.now()));
                assert hpke.publicKey().equals(Base64.getUrlEncoder().withoutPadding().encodeToString(recipientPublic));

                byte[] sharedSecret = GatorWSHpke.decapsulate(recipient, encapsulation);
                assert Arrays.equals(sharedSecret, hex("fe0e18c9f024ce43799ae393c7e8fe8fce9d218875e8227b0187c04e7d2ea1fc"));

                GatorWSHpke.Context context = GatorWSHpke.context(sharedSecret, 1, 16,
                        hex("4f6465206f6e2061204772656369616e2055726e"));
                assert Arrays.equals(context.key(), hex("4531685d41d65f03dc48f6b8302c05b0"));
                assert Arrays.equals(context.baseNonce(), hex("56d890e5accaaf011cff4b7d"));
                assert Arrays.equals(context.exporterSecret(), hex("45ff1c2e220db587171952c0592d5f5ebe103f1561a2614e38f2ffd47e99e3f8"));

                byte[] plaintext = GatorWSHpke.open(context.key(), context.baseNonce(), 0,
                        "Count-0".getBytes(StandardCharsets.US_ASCII),
                        hex("f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a96d8770ac83d07bea87e13c512a"));
                assert Arrays.equals(plaintext, hex("4265617574792069732074727574682c20747275746820626561757479"));
        }

        private static void productionEnvelopeRoundTrip() throws Exception {
                GatorWSKeyManager.Generation generation = new GatorWSKeyManager(500, Duration.ofDays(1)).acquire();
                GatorWSHpke server = new GatorWSHpke(generation);
                KEM.Encapsulated encapsulated = KEM.getInstance("DHKEM")
                        .newEncapsulator(generation.keyPair().getPublic()).encapsulate();
                GatorWSHpke.Context context = GatorWSHpke.context(encapsulated.key().getEncoded(), 2, 32,
                        "gator-websockets-v1".getBytes(StandardCharsets.UTF_8));
                String authentication = "{\"type\":\"authenticateme\"}";
                String initialAad = "gator-ws-v1|" + generation.id() + "|hpke|0";
                byte[] initialCiphertext = GatorWSHpke.seal(context.key(), context.baseNonce(), 0,
                        initialAad.getBytes(StandardCharsets.UTF_8), authentication.getBytes(StandardCharsets.UTF_8));
                String initialEnvelope = envelope(generation.id(), encode(encapsulated.encapsulation()), 0, encode(initialCiphertext));
                assert authentication.equals(server.open(initialEnvelope));

                byte[] clientMaterial = GatorWSHpke.export(context, "gator-ws-v1/client-to-server", 44);
                byte[] serverMaterial = GatorWSHpke.export(context, "gator-ws-v1/server-to-client", 44);
                String request = "{\"type\":\"getuserlist\"}";
                String requestAad = "gator-ws-v1|" + generation.id() + "|client-to-server|0";
                byte[] requestCiphertext = GatorWSHpke.seal(Arrays.copyOf(clientMaterial, 32),
                        Arrays.copyOfRange(clientMaterial, 32, 44), 0, requestAad.getBytes(StandardCharsets.UTF_8),
                        request.getBytes(StandardCharsets.UTF_8));
                String requestEnvelope = envelope(generation.id(), null, 0, encode(requestCiphertext));
                assert request.equals(server.open(requestEnvelope));
                expectFailure(() -> server.open(requestEnvelope));

                byte[] tampered = GatorWSHpke.seal(Arrays.copyOf(clientMaterial, 32),
                        Arrays.copyOfRange(clientMaterial, 32, 44), 1,
                        ("gator-ws-v1|" + generation.id() + "|client-to-server|1").getBytes(StandardCharsets.UTF_8),
                        request.getBytes(StandardCharsets.UTF_8));
                tampered[tampered.length - 1] ^= 1;
                expectFailure(() -> server.open(envelope(generation.id(), null, 1, encode(tampered))));

                String response = "{\"type\":\"userslist\"}";
                JsonObject responseEnvelope = JsonParser.parseString(server.seal(response)).getAsJsonObject();
                String responseAad = "gator-ws-v1|" + generation.id() + "|server-to-client|0";
                byte[] opened = GatorWSHpke.open(Arrays.copyOf(serverMaterial, 32),
                        Arrays.copyOfRange(serverMaterial, 32, 44), 0, responseAad.getBytes(StandardCharsets.UTF_8),
                        Base64.getUrlDecoder().decode(responseEnvelope.get("ciphertext").getAsString()));
                assert response.equals(new String(opened, StandardCharsets.UTF_8));
        }

        private static void rotatesAtConnectionLimit() {
                GatorWSKeyManager manager = new GatorWSKeyManager(2, Duration.ofDays(1));
                String first = manager.acquire().id();
                assert first.equals(manager.acquire().id());
                assert !first.equals(manager.acquire().id());
        }

        private static byte[] reverse(byte[] value) {
                byte[] reversed = value.clone();
                for(int i = 0; i < reversed.length / 2; i++) {
                        byte current = reversed[i];
                        reversed[i] = reversed[reversed.length - 1 - i];
                        reversed[reversed.length - 1 - i] = current;
                }
                return reversed;
        }

        private static byte[] hex(String value) {
                return HEX.parseHex(value);
        }

        private static String envelope(String keyId, String encapsulation, long sequence, String ciphertext) {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("version", 1);
                envelope.addProperty("keyId", keyId);
                if(encapsulation != null) envelope.addProperty("encapsulation", encapsulation);
                envelope.addProperty("sequence", sequence);
                envelope.addProperty("ciphertext", ciphertext);
                return envelope.toString();
        }

        private static String encode(byte[] value) {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }

        private static void expectFailure(CheckedOperation operation) throws Exception {
                try {
                        operation.run();
                        throw new AssertionError("Expected encrypted envelope rejection");
                } catch(java.security.GeneralSecurityException expected) {
                }
        }

        @FunctionalInterface
        private interface CheckedOperation {
                void run() throws Exception;
        }
}
