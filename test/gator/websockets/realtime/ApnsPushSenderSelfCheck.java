package gator.websockets.realtime;

public final class ApnsPushSenderSelfCheck {
    private ApnsPushSenderSelfCheck() {}

    public static void run() {
        assert ApnsPushSender.request("{\"type\":\"login.authorization\",\"application\":\"Gator Mail\"}")
                .getAsJsonObject("aps").getAsJsonObject("alert").get("body").getAsString().contains("Gator Mail");
        byte[] der = {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02};
        byte[] raw = ApnsPushSender.jose(der);
        assert raw.length == 64 && raw[31] == 1 && raw[63] == 2;
        assert ApnsPushSender.permanent(410);
        assert !ApnsPushSender.permanent(503);
    }
}
