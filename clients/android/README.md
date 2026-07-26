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

client.connect(accessToken);
// Después de recibir onState("authenticated"):
client.subscribe(List.of("screen/orders"));
JsonObject payload = new JsonObject();
payload.addProperty("type", "order.updated");
client.publish("topic", List.of("screen/orders"), payload);
```

También expone `unsubscribe`, `presence` y `ack`. El cliente deduplica y
confirma automáticamente los mensajes v2 válidos.
