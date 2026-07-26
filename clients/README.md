# Clientes oficiales

Los tres clientes implementan HPKE y el protocolo distribuido v2. Comparten
`connect`, `send`, `publish`, `subscribe`, `unsubscribe`, `presence`, `ack` y
`close`; además deduplican y confirman automáticamente las entregas v2.

| Directorio | Plataforma | Transporte | Criptografía |
| --- | --- | --- | --- |
| `javascript` | Navegadores | WebSocket nativo | hpke-js y Web Crypto |
| `android` | Android 8/API 26+ | OkHttp | Bouncy Castle y JCA |
| `ios` | iOS 13+ | URLSessionWebSocketTask | CryptoKit |

Cada directorio contiene sus instrucciones y su prueba de protocolo.
Los tres reciben un access token JWT mediante `connect`; la obtención y
renovación del token corresponde a la integración de Keycloak de cada app.

## Ciclo de integración

1. La aplicación obtiene o renueva el access token.
2. Crea el cliente con el endpoint primario.
3. Configura callbacks antes de conectar.
4. Llama `connect(accessToken)`.
5. Después de `authenticated`, recrea suscripciones y publica.
6. Ante cierre, obtiene un token vigente y reconecta.
7. Si el primario no responde, crea otro cliente con el endpoint secundario.

Las librerías no almacenan refresh tokens ni credenciales de Keycloak. Tampoco
migran una conexión existente: reconexión, backoff y selección de endpoint son
responsabilidad de la aplicación.

## Semántica común

- `publish` acepta `connection`, `user`, `topic` o `tenant`.
- `clientMessageId` permite reintentar una publicación sin duplicarla.
- Los mensajes entrantes se deduplican y reciben ACK automáticamente.
- Las suscripciones pertenecen a la conexión y deben recrearse al reconectar.
- `presence` devuelve usuarios y número de conexiones activas.

Consulta el [contrato v2](../docs/protocol-v2.md) y la sección de failover en
[operación](../docs/operations.md).
