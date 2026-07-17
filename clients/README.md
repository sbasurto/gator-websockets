# Clientes oficiales

Los tres clientes implementan el protocolo HPKE de `gator-websockets` y
comparten la API mínima `connect`, `send` y `close`.

| Directorio | Plataforma | Transporte | Criptografía |
| --- | --- | --- | --- |
| `javascript` | Navegadores | WebSocket nativo | hpke-js y Web Crypto |
| `android` | Android 8/API 26+ | OkHttp | Bouncy Castle y JCA |
| `ios` | iOS 13+ | URLSessionWebSocketTask | CryptoKit |

Cada directorio contiene sus instrucciones y su prueba de protocolo.
