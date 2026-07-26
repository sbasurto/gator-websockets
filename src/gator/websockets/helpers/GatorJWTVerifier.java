package gator.websockets.helpers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Validates Keycloak RS256 access tokens against its JWKS endpoint. */
public final class GatorJWTVerifier {
        private static final Base64.Decoder BASE64 = Base64.getUrlDecoder();
        private final String issuer;
        private final String audience;
        private final URI jwksUri;
        private final Duration clockSkew;
        private final Duration cacheDuration;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private volatile Map<String, PublicKey> keys = Map.of();
        private volatile Instant keysExpireAt = Instant.EPOCH;
        private volatile Instant keysRefreshedAt = Instant.EPOCH;

        public GatorJWTVerifier(GatorWSProperties properties) {
                this(properties.getJwtIssuer(), properties.getJwtAudience(), properties.getJwtJwksUri(),
                        Duration.ofSeconds(properties.getJwtClockSkewSeconds()),
                        Duration.ofSeconds(properties.getJwtJwksCacheSeconds()));
        }

        GatorJWTVerifier(String issuer, String audience, String jwksUri, Duration clockSkew, Duration cacheDuration) {
                this.issuer = issuer;
                this.audience = audience;
                this.jwksUri = URI.create(jwksUri);
                this.clockSkew = clockSkew;
                this.cacheDuration = cacheDuration;
                String scheme = this.jwksUri.getScheme();
                if(!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && isLoopback(this.jwksUri))) {
                        throw new IllegalArgumentException("jwtJwksUri must use HTTPS or loopback HTTP");
                }
        }

        public Identity verify(String token) throws Exception {
                if(token == null || token.length() > 16_384) throw new IllegalArgumentException("Invalid access token");
                String[] parts = token.split("\\.", -1);
                if(parts.length != 3) throw new IllegalArgumentException("Invalid access token");
                JsonObject header = json(parts[0]);
                if(!"RS256".equals(string(header, "alg"))) throw new IllegalArgumentException("Unsupported token algorithm");
                String kid = string(header, "kid");
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(key(kid));
                signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if(!signature.verify(decode(parts[2]))) throw new IllegalArgumentException("Invalid access token signature");

                JsonObject claims = json(parts[1]);
                Instant now = Instant.now();
                if(!issuer.equals(string(claims, "iss")) || !hasAudience(claims.get("aud"), audience)) {
                        throw new IllegalArgumentException("Invalid token issuer or audience");
                }
                long exp = number(claims, "exp");
                if(now.minus(clockSkew).getEpochSecond() >= exp) throw new IllegalArgumentException("Access token expired");
                if(claims.has("nbf") && now.plus(clockSkew).getEpochSecond() < number(claims, "nbf")) {
                        throw new IllegalArgumentException("Access token is not active");
                }
                String subject = string(claims, "sub");
                String name = claims.has("preferred_username") ? string(claims, "preferred_username") : subject;
                return new Identity(subject, name, roles(claims));
        }

        private PublicKey key(String kid) throws Exception {
                Instant now = Instant.now();
                if(now.isAfter(keysExpireAt)) refreshKeys();
                PublicKey key = keys.get(kid);
                if(key == null && now.isAfter(keysRefreshedAt.plusSeconds(5))) {
                    refreshKeys();
                    key = keys.get(kid);
                }
                if(key == null) throw new IllegalArgumentException("Unknown token signing key");
                return key;
        }

        private synchronized void refreshKeys() throws Exception {
                HttpRequest request = HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if(response.statusCode() != 200 || response.body().length > 1_048_576) {
                        throw new IllegalStateException("Cannot load token signing keys");
                }
                Map<String, PublicKey> loaded = new HashMap<>();
                for(var element: JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("keys")) {
                        JsonObject value = element.getAsJsonObject();
                        if(!"RSA".equals(string(value, "kty")) || !"sig".equals(string(value, "use"))) continue;
                        BigInteger modulus = new BigInteger(1, decode(string(value, "n")));
                        if(modulus.bitLength() < 2048) continue;
                        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                                modulus, new BigInteger(1, decode(string(value, "e")))));
                        loaded.put(string(value, "kid"), key);
                }
                if(loaded.isEmpty()) throw new IllegalStateException("Token signing keys are empty");
                keys = Map.copyOf(loaded);
                keysRefreshedAt = Instant.now();
                keysExpireAt = keysRefreshedAt.plus(cacheDuration);
        }

        private Set<String> roles(JsonObject claims) {
                Set<String> roles = new HashSet<>(Set.of("messages:send", "messages:receive"));
                JsonObject realmAccess = claims.getAsJsonObject("realm_access");
                addStrings(roles, realmAccess == null ? null : realmAccess.getAsJsonArray("roles"));
                JsonObject resources = claims.getAsJsonObject("resource_access");
                JsonObject client = resources == null ? null : resources.getAsJsonObject(audience);
                addStrings(roles, client == null ? null : client.getAsJsonArray("roles"));
                return Set.copyOf(roles);
        }

        private static void addStrings(Set<String> values, JsonArray source) {
                if(source != null) source.forEach(value -> values.add(value.getAsString()));
        }

        private static boolean hasAudience(com.google.gson.JsonElement value, String expected) {
                if(value == null || value.isJsonNull()) return false;
                if(value.isJsonPrimitive()) return expected.equals(value.getAsString());
                for(var item: value.getAsJsonArray()) if(expected.equals(item.getAsString())) return true;
                return false;
        }

        private static JsonObject json(String value) {
                try { return JsonParser.parseString(new String(decode(value), StandardCharsets.UTF_8)).getAsJsonObject(); }
                catch(Exception error) { throw new IllegalArgumentException("Invalid access token", error); }
        }

        private static byte[] decode(String value) {
                try { return BASE64.decode(value); }
                catch(Exception error) { throw new IllegalArgumentException("Invalid access token", error); }
        }

        private static String string(JsonObject object, String name) {
                if(object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
                        throw new IllegalArgumentException("Invalid access token");
                }
                String value = object.get(name).getAsString();
                if(value.isBlank()) throw new IllegalArgumentException("Invalid access token");
                return value;
        }

        private static long number(JsonObject object, String name) {
                try { return object.get(name).getAsLong(); }
                catch(Exception error) { throw new IllegalArgumentException("Invalid access token", error); }
        }

        private static boolean isLoopback(URI uri) {
                try { return InetAddress.getByName(uri.getHost()).isLoopbackAddress(); }
                catch(Exception error) { return false; }
        }

        public record Identity(String subject, String name, Set<String> scopes) {}
}
