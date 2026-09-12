package gator.websockets.realtime;

import java.net.http.HttpTimeoutException;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;

public final class ApnsPushSenderSelfCheck {
    private static final String DEVICE = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);
    private static final String PAYLOAD = "{\"application\":\"Test\",\"purpose\":\"Test notification\"}";
    private static final String PRODUCTION = "api.push.apple.com";
    private static final String SANDBOX = "api.sandbox.push.apple.com";
    private ApnsPushSenderSelfCheck() {}

    public static void run() throws Exception {
        var alert = ApnsPushSender.request("{\"type\":\"login.authorization\",\"application\":\"Gator Mail\","
                + "\"purpose\":\"Abrir el correo\",\"account\":\"sbasurto\"}")
                .getAsJsonObject("aps").getAsJsonObject("alert");
        assert "Gator Mail".equals(alert.get("title").getAsString());
        assert "Abrir el correo · Cuenta sbasurto".equals(alert.get("body").getAsString());
        byte[] der = {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02};
        byte[] raw = ApnsPushSender.jose(der);
        assert raw.length == 64 && raw[31] == 1 && raw[63] == 2;
        assert ApnsPushSender.permanent(410);
        assert !ApnsPushSender.permanent(503);
        learnsEnvironmentPerDevice();
        supportsProductionFromSandboxDefault();
        neverRetriesAcceptedOrUncertainDelivery();
        neverRetriesOtherRejections();
        alternateFailureIsReturned();
        cacheIsBounded();
    }

    private static ApnsPushSender sender(boolean sandbox, ApnsPushSender.Transport transport) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return new ApnsPushSender(generator.generateKeyPair().getPrivate(), "TEAM", "KEY",
                "com.example.test", sandbox, transport);
    }

    private static void learnsEnvironmentPerDevice() throws Exception {
        List<String> hosts = new ArrayList<>();
        var sender = sender(false, request -> {
            String host = request.uri().getHost();
            hosts.add(host);
            assert "alert".equals(request.headers().firstValue("apns-push-type").orElseThrow());
            assert "com.example.test".equals(request.headers().firstValue("apns-topic").orElseThrow());
            return request.uri().getPath().endsWith(DEVICE) && host.equals(PRODUCTION)
                    ? new ApnsPushSender.Reply(400, "{\"reason\":\"BadDeviceToken\"}")
                    : new ApnsPushSender.Reply(200, "");
        });
        assert sender.send(DEVICE, PAYLOAD).sent();
        assert sender.send(DEVICE, PAYLOAD).sent();
        assert sender.send(OTHER, PAYLOAD).sent();
        assert hosts.equals(List.of(PRODUCTION, SANDBOX, SANDBOX, PRODUCTION)) : hosts;
    }

    private static void supportsProductionFromSandboxDefault() throws Exception {
        List<String> hosts = new ArrayList<>();
        var sender = sender(true, request -> {
            hosts.add(request.uri().getHost());
            return new ApnsPushSender.Reply(hosts.size() == 1 ? 400 : 200,
                    hosts.size() == 1 ? "{\"reason\":\"BadDeviceToken\"}" : "");
        });
        assert sender.send(DEVICE, PAYLOAD).sent();
        assert sender.send(DEVICE, PAYLOAD).sent();
        assert hosts.equals(List.of(SANDBOX, PRODUCTION, PRODUCTION));
    }

    private static void neverRetriesAcceptedOrUncertainDelivery() throws Exception {
        for (int status : new int[]{200, 429, 500, 503}) {
            List<String> calls = new ArrayList<>();
            var sender = sender(false, request -> {
                calls.add(request.uri().getHost());
                return new ApnsPushSender.Reply(status, "");
            });
            var result = sender.send(DEVICE, PAYLOAD);
            assert result.sent() == (status == 200);
            assert calls.size() == 1;
        }
        List<String> calls = new ArrayList<>();
        var sender = sender(false, request -> {
            calls.add(request.uri().getHost());
            throw new HttpTimeoutException("Synthetic timeout");
        });
        var result = sender.send(DEVICE, PAYLOAD);
        assert !result.sent() && !result.permanent() && calls.size() == 1;
        assert sender.send("invalid-device", PAYLOAD).permanent();
        assert calls.size() == 1;
    }

    private static void neverRetriesOtherRejections() throws Exception {
        for (var reply : List.of(new ApnsPushSender.Reply(403, "{\"reason\":\"InvalidProviderToken\"}"),
                new ApnsPushSender.Reply(410, "{\"reason\":\"Unregistered\"}"),
                new ApnsPushSender.Reply(400, "{\"reason\":\"DeviceTokenNotForTopic\"}"),
                new ApnsPushSender.Reply(400, "not-json BadDeviceToken"),
                new ApnsPushSender.Reply(400, "{\"reason\":false}"),
                new ApnsPushSender.Reply(403, "{\"reason\":\"BadDeviceToken\"}"))) {
            List<String> calls = new ArrayList<>();
            var sender = sender(false, request -> {
                calls.add(request.uri().getHost());
                return reply;
            });
            assert !sender.send(DEVICE, PAYLOAD).sent();
            assert calls.size() == 1 : reply;
        }
    }

    private static void alternateFailureIsReturned() throws Exception {
        for (int secondStatus : new int[]{400, 503}) {
            List<String> calls = new ArrayList<>();
            var sender = sender(false, request -> {
                calls.add(request.uri().getHost());
                return new ApnsPushSender.Reply(calls.size() % 2 == 1 ? 400 : secondStatus,
                        "{\"reason\":\"BadDeviceToken\"}");
            });
            var result = sender.send(DEVICE, PAYLOAD);
            assert !result.sent() && result.permanent() == (secondStatus == 400);
            assert result.error().contains("APNs HTTP " + secondStatus);
            sender.send(DEVICE, PAYLOAD);
            assert calls.equals(List.of(PRODUCTION, SANDBOX, PRODUCTION, SANDBOX));
        }
    }

    private static void cacheIsBounded() throws Exception {
        List<String> calls = new ArrayList<>();
        var sender = sender(false, request -> {
            String host = request.uri().getHost();
            calls.add(host);
            return host.equals(PRODUCTION)
                    ? new ApnsPushSender.Reply(400, "{\"reason\":\"BadDeviceToken\"}")
                    : new ApnsPushSender.Reply(200, "");
        });
        for (int i = 0; i < 1025; i++) assert sender.send(String.format("%064x", i), PAYLOAD).sent();
        calls.clear();
        assert sender.send(String.format("%064x", 0), PAYLOAD).sent();
        assert calls.equals(List.of(PRODUCTION, SANDBOX));
    }
}
