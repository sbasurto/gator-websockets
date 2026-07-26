# Cliente JavaScript

Cliente de navegador para `gator-websockets` con HPKE y cifrado bidireccional
AES-256-GCM.

```bash
npm install
npm test
```

```javascript
import { GatorWebSocketClient } from "@gator/websocket-client";

const client = new GatorWebSocketClient("wss://example.com:8080", {
  onMessage: console.log,
  onEvent: console.log,
});

await client.connect(accessToken);
await client.subscribe(["screen/orders"]);
await client.publish("topic", ["screen/orders"], {
  type: "order.updated", data: { orderId: "123" },
});
```

También expone `unsubscribe`, `presence` y `ack`. Los mensajes v2 recibidos se
deduplican y confirman automáticamente después de descifrarlos y validarlos.

El origen de la página (`location.origin`) debe aparecer en `allowedOrigins`
del servidor.
