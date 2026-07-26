package gator.websockets.helpers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public final class GatorJWTVerifierSelfCheck {
        private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

        private GatorJWTVerifierSelfCheck() {}

        public static void run() throws Exception {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                byte[] jwks = jwks((RSAPublicKey) pair.getPublic()).getBytes(StandardCharsets.UTF_8);
                server.createContext("/certs", exchange -> {
                        exchange.sendResponseHeaders(200, jwks.length);
                        exchange.getResponseBody().write(jwks);
                        exchange.close();
                });
                server.start();
                try {
                        String issuer = "https://id.example/realms/gator";
                        GatorJWTVerifier verifier = new GatorJWTVerifier(issuer, "gator-websockets",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
                                Duration.ofSeconds(1), Duration.ofMinutes(5));
                        String token = token(pair, issuer, "gator-websockets", Instant.now().plusSeconds(60));
                        assert "user-1".equals(verifier.verify(token).subject());
                        expectFailure(() -> verifier.verify(token(pair, issuer, "wrong", Instant.now().plusSeconds(60))));
                        expectFailure(() -> verifier.verify(token(pair, issuer, "gator-websockets", Instant.now().minusSeconds(5))));
			String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
			expectFailure(() -> verifier.verify(tampered));
                } finally {
                        server.stop(0);
                }
        }

        private static String token(KeyPair pair, String issuer, String audience, Instant expires) throws Exception {
                JsonObject header = new JsonObject();
                header.addProperty("alg", "RS256");
                header.addProperty("kid", "test-key");
                JsonObject claims = new JsonObject();
                claims.addProperty("iss", issuer);
                claims.addProperty("aud", audience);
                claims.addProperty("sub", "user-1");
                claims.addProperty("preferred_username", "user");
                claims.addProperty("exp", expires.getEpochSecond());
                String input = encode(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                        + encode(claims.toString().getBytes(StandardCharsets.UTF_8));
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(pair.getPrivate());
                signature.update(input.getBytes(StandardCharsets.US_ASCII));
                return input + "." + encode(signature.sign());
        }

        private static String jwks(RSAPublicKey key) {
                JsonObject jwk = new JsonObject();
                jwk.addProperty("kid", "test-key");
                jwk.addProperty("kty", "RSA");
                jwk.addProperty("use", "sig");
                jwk.addProperty("n", encode(unsigned(key.getModulus())));
                jwk.addProperty("e", encode(unsigned(key.getPublicExponent())));
                JsonArray keys = new JsonArray();
                keys.add(jwk);
                JsonObject result = new JsonObject();
                result.add("keys", keys);
                return result.toString();
        }

        private static byte[] unsigned(BigInteger value) {
                byte[] bytes = value.toByteArray();
                return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
        }

        private static String encode(byte[] value) {
                return BASE64.encodeToString(value);
        }

        private static void expectFailure(Checked operation) throws Exception {
                try {
                        operation.run();
                        throw new AssertionError("Expected JWT rejection");
                } catch(IllegalArgumentException expected) {
                }
        }

        @FunctionalInterface
        private interface Checked { void run() throws Exception; }
}
