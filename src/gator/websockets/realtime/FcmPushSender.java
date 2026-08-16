package gator.websockets.realtime;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Sends wake-up notifications through FCM HTTP v1 using ADC. */
final class FcmPushSender {
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private final GoogleCredentials credentials;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI endpoint;

    FcmPushSender(String projectId) throws Exception {
        if(!projectId.matches("[a-z0-9][a-z0-9-]{4,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Invalid FCM project id");
        }
        endpoint = URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send");
        credentials = GoogleCredentials.getApplicationDefault().createScoped(Set.of(SCOPE));
    }

    Result send(String token, String payload) {
        try {
            credentials.refreshIfExpired();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(request(token, payload).toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() >= 200 && response.statusCode() < 300) return new Result(true, false, null);
            String body = response.body() == null ? "" : response.body();
            return new Result(false, permanent(response.statusCode(), body), "FCM HTTP " + response.statusCode());
        } catch(Exception error) {
            return new Result(false, false, error.getClass().getSimpleName());
        }
    }

    static JsonObject request(String token, String encodedPayload) {
        JsonObject source = JsonParser.parseString(encodedPayload).getAsJsonObject();
        JsonObject data = new JsonObject();
        for(Map.Entry<String, JsonElement> entry: source.entrySet()) {
            if(entry.getValue().isJsonPrimitive()) data.addProperty(entry.getKey(), entry.getValue().getAsString());
        }
        JsonObject notification = new JsonObject();
        notification.addProperty("title", string(source, "application", "Gator"));
        notification.addProperty("body", ApnsPushSender.notificationBody(source));
        JsonObject message = new JsonObject();
        message.addProperty("token", token);
        message.add("notification", notification);
        message.add("data", data);
        JsonObject root = new JsonObject();
        root.add("message", message);
        return root;
    }

    static int retrySeconds(int attempts) {
        return Math.min(300, 1 << Math.min(Math.max(attempts, 1), 8));
    }

    static boolean permanent(int status, String body) {
        return status == 400 || status == 404 || body.contains("UNREGISTERED");
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : fallback;
    }

    record Result(boolean sent, boolean permanent, String error) {}
}
