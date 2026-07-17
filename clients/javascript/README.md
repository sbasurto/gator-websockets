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

await client.connect("usuario-id", "passphrase");
await client.send({ type: "getuserlist" });
```

El origen de la página (`location.origin`) debe aparecer en `allowedOrigins`
del servidor.
