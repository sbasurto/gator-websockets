package gator.websockets.realtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Sends APNs alert notifications with Apple's token-based HTTP/2 API. */
final class ApnsPushSender {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;
    private final String teamId;
    private final String keyId;
    private final String bundleId;
    private final PrivateKey key;
    private String jwt;
    private long jwtCreatedAt;

    ApnsPushSender(String keyFile, String teamId, String keyId, String bundleId, boolean sandbox) throws Exception {
        this.teamId = teamId;
        this.keyId = keyId;
        this.bundleId = bundleId;
        endpoint = URI.create(sandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com");
        String pem = Files.readString(Path.of(keyFile), StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        key = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    FcmPushSender.Result send(String token, String payload) {
        try {
            if(!token.matches("[A-Fa-f0-9]{32,256}")) return new FcmPushSender.Result(false, true, "Invalid APNs token");
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/3/device/" + token))
                    .version(HttpClient.Version.HTTP_2).timeout(Duration.ofSeconds(15))
                    .header("authorization", "bearer " + token())
                    .header("apns-topic", bundleId)
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request(payload).toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() == 200) return new FcmPushSender.Result(true, false, null);
            String body = response.body() == null ? "" : response.body();
            return new FcmPushSender.Result(false, permanent(response.statusCode()),
                    "APNs HTTP " + response.statusCode() + (body.isBlank() ? "" : " " + body));
        } catch(Exception error) {
            return new FcmPushSender.Result(false, false, error.getClass().getSimpleName());
        }
    }

    static JsonObject request(String encodedPayload) {
        JsonObject source = JsonParser.parseString(encodedPayload).getAsJsonObject();
        JsonObject alert = new JsonObject();
        alert.addProperty("title", string(source, "application", "Gator"));
        alert.addProperty("body", notificationBody(source));
        JsonObject aps = new JsonObject();
        aps.add("alert", alert);
        aps.addProperty("sound", "default");
        JsonObject root = source.deepCopy();
        root.add("aps", aps);
        return root;
    }

    private synchronized String token() throws Exception {
        long now = Instant.now().getEpochSecond();
        if(jwt != null && now - jwtCreatedAt < 50 * 60) return jwt;
        String header = encode("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
        String claims = encode("{\"iss\":\"" + teamId + "\",\"iat\":" + now + "}");
        String unsigned = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        jwt = unsigned + "." + BASE64.encodeToString(jose(signature.sign()));
        jwtCreatedAt = now;
        return jwt;
    }

    static byte[] jose(byte[] der) {
        if(der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("Invalid ECDSA signature");
        int offset = der[1] < 0 ? 2 + (der[1] & 0x7f) : 2;
        if(der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int rLength = der[offset++] & 0xff;
        byte[] r = Arrays.copyOfRange(der, offset, offset + rLength);
        offset += rLength;
        if(der[offset++] != 0x02) throw new IllegalArgumentException("Invalid ECDSA signature");
        int sLength = der[offset++] & 0xff;
        byte[] s = Arrays.copyOfRange(der, offset, offset + sLength);
        byte[] raw = new byte[64];
        System.arraycopy(r, Math.max(0, r.length - 32), raw, 32 - Math.min(32, r.length), Math.min(32, r.length));
        System.arraycopy(s, Math.max(0, s.length - 32), raw, 64 - Math.min(32, s.length), Math.min(32, s.length));
        return raw;
    }

    static boolean permanent(int status) { return status == 400 || status == 403 || status == 410; }

    private static String encode(String value) {
        return BASE64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : fallback;
    }

    static String notificationBody(JsonObject source) {
        String purpose = string(source, "purpose", "Autorizar acceso");
        String account = string(source, "account", "").trim();
        return account.isEmpty() ? purpose : purpose + " · Cuenta " + account;
    }
}
