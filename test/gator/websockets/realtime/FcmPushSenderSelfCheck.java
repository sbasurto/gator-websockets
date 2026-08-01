package gator.websockets.realtime;

import com.google.gson.JsonObject;

public final class FcmPushSenderSelfCheck {
    private FcmPushSenderSelfCheck() {}

    public static void run() {
        JsonObject request = FcmPushSender.request("token", "{\"type\":\"login.authorization\",\"application\":\"Gator Mail\"}");
        assert "token".equals(request.getAsJsonObject("message").get("token").getAsString());
        assert "login.authorization".equals(request.getAsJsonObject("message").getAsJsonObject("data").get("type").getAsString());
        assert FcmPushSender.retrySeconds(1) == 2;
        assert FcmPushSender.retrySeconds(20) == 256;
        assert FcmPushSender.permanent(400, "INVALID_ARGUMENT");
        assert !FcmPushSender.permanent(503, "UNAVAILABLE");
    }
}
