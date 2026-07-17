# Cliente Android

Librería Java para Android 8/API 26 o posterior. Usa OkHttp para WebSocket,
Bouncy Castle para X25519 y las primitivas criptográficas de la plataforma.

```bash
../../gradlew -p . check
```

```java
GatorWebSocketClient client = new GatorWebSocketClient(
    "wss://example.com:8080",
    new GatorWebSocketClient.Listener() {
        @Override public void onMessage(JsonObject message) {
            // Procesar mensaje.
        }
    }
);

client.connect("usuario-id", "passphrase");
// Después de recibir onState("authenticated"):
JsonObject message = new JsonObject();
message.addProperty("type", "getuserlist");
client.send(message);
```
