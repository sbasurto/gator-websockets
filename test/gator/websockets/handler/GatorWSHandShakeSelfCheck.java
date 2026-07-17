package gator.websockets.handler;

import java.util.ArrayList;
import java.util.Set;

public final class GatorWSHandShakeSelfCheck {
        private GatorWSHandShakeSelfCheck() {}

        public static void run() {
                assert handshake(Set.of("https://app.example.com"), "https://app.example.com");
                assert !handshake(Set.of("https://app.example.com"), "https://evil.example.com");
                assert handshake(Set.of(), null);
                assert handshake(Set.of("*"), "https://any.example.com");
        }

        private static boolean handshake(Set<String> allowedOrigins, String origin) {
                ArrayList<String> request = new ArrayList<>();
                request.add("GET / HTTP/1.1");
                request.add("Upgrade: websocket");
                request.add("Connection: keep-alive, Upgrade");
                request.add("Sec-WebSocket-Version: 13");
                request.add("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==");
                if(origin != null) request.add("Origin: " + origin);
                return new GatorWSHandShakeHandler(allowedOrigins).procesaSaludo(request);
        }
}
