# Protocolo distribuido v2

## Transporte

Todos los objetos v2 viajan como texto JSON dentro del canal HPKE establecido
después del handshake WebSocket. No se envía JSON v2 en claro.

El servidor obtiene `tenantId`, `applicationId`, `userId`, `connectionId` y
`serverId` de la sesión autenticada. El cliente no puede establecerlos.

## Identidad de sesión

Después de validar el JWT, `authsuccess` incluye:

```json
{
  "type": "authsuccess",
  "data": {
    "authentication": "jwt",
    "connectionId": "uuid",
    "serverId": "uuid",
    "tenantId": "example",
    "applicationId": "application",
    "userId": "keycloak-sub"
  }
}
```

`userId` es el claim `sub`; ningún campo de publicación puede reemplazarlo.

## Forma común

Las solicitudes v2 contienen:

```json
{"v":2,"op":"operation"}
```

Las respuestas usan la misma versión y una operación que describe el resultado.
Los errores tienen:

```json
{"v":2,"op":"error","reason":"reason_code"}
```

## Publicar

Solicitud:

```json
{
  "v": 2,
  "op": "publish",
  "clientMessageId": "uuid-generado-por-el-cliente",
  "target": {
    "kind": "user",
    "ids": ["user-id"]
  },
  "payload": {
    "type": "chat.message",
    "data": {"text": "hola"}
  }
}
```

Respuesta:

```json
{
  "v": 2,
  "op": "accepted",
  "messageId": "uuid-generado-por-el-servidor",
  "clientMessageId": "uuid-generado-por-el-cliente"
}
```

`clientMessageId` hace idempotente la publicación dentro de tenant, aplicación
y emisor. Repetirlo devuelve el `messageId` ya existente y no crea nuevas
entregas.

### Destinos

| `kind` | `ids` | Semántica |
| --- | --- | --- |
| `connection` | 1 a 100 UUID | Conexiones exactas activas. |
| `user` | 1 a 100 user IDs | Todas las conexiones activas; queda offline si ninguna existe. |
| `topic` | 1 a 100 tópicos | Conexiones suscritas al publicar. |
| `tenant` | Omitido o vacío | Todas las conexiones activas del tenant/aplicación. |

`tenant` requiere `broadcast:tenant`. Los demás requieren `messages:send`.

El payload debe ser un objeto y contener `type`. El servidor lo conserva sin
interpretar el resto de sus campos.

## Mensaje entregado

```json
{
  "v": 2,
  "op": "message",
  "messageId": "uuid",
  "clientMessageId": "uuid",
  "tenantId": "example",
  "applicationId": "application",
  "sender": {"userId": "keycloak-sub"},
  "target": {"kind": "user", "ids": ["user-id"]},
  "payload": {"type": "chat.message", "data": {"text": "hola"}},
  "createdAt": "2026-07-26T16:00:00Z"
}
```

El cliente debe validar `messageId`, deduplicar y enviar ACK. Los clientes
oficiales lo hacen automáticamente y solo llaman `onMessage` para la primera
aparición dentro de su cache actual.

## ACK

Solicitud:

```json
{"v":2,"op":"ack","messageId":"uuid","status":"delivered"}
```

Respuesta:

```json
{"v":2,"op":"acked","messageId":"uuid"}
```

El único estado admitido es `delivered`: el cliente descifró y validó el
mensaje. El ACK solo afecta la entrega de la conexión autenticada que lo envía.

## Suscribir y desuscribir

```json
{"v":2,"op":"subscribe","topics":["screen/orders"]}
{"v":2,"op":"unsubscribe","topics":["screen/orders"]}
```

Respuestas:

```json
{"v":2,"op":"subscribed","topics":["screen/orders"]}
{"v":2,"op":"unsubscribed","topics":["screen/orders"]}
```

Reglas de tópico:

- 1 a 100 por operación;
- 1 a 128 caracteres;
- caracteres permitidos: letras, números, `.`, `_`, `/` y `-`;
- no puede iniciar con `/`;
- no puede contener `..`;
- no hay comodines.

La suscripción pertenece a una conexión, no al usuario. Debe recrearse después
de reconectar.

## Presencia

Solicitud:

```json
{"v":2,"op":"presence"}
```

Respuesta:

```json
{
  "v": 2,
  "op": "presence",
  "users": [
    {"userId": "user-1", "connections": 2}
  ]
}
```

Solo incluye conexiones del mismo tenant/aplicación cuyo servidor tenga un
heartbeat dentro del lease.

## Errores comunes

| `reason` | Causa |
| --- | --- |
| `unsupported_operation` | `op` no reconocido. |
| `not_authorized` | Falta scope. |
| `request_failed` | Error interno o de persistencia. |
| `target is required` | Falta objeto destino. |
| `Invalid target kind` | Destino no soportado. |
| `target ids must contain 1 to 100 values` | IDs vacíos o excesivos. |
| `payload.type is required` | Payload inválido. |
| `Invalid topic` | Tópico fuera de contrato. |
| `Invalid ACK status` | Estado distinto de `delivered`. |

Los mensajes de validación pueden aparecer como `reason`; el cliente debe
tratar valores desconocidos como error de solicitud y no depender de texto
humano estable.

## Estado de entrega

```text
pending ── envío ──► dispatched ── ACK ──► delivered
   ▲                     │
   └──── reintento ──────┘

pending/dispatched ── TTL vencido ──► expired
```

- `pending`: lista o esperando conexión.
- `dispatched`: enviada, esperando ACK.
- `delivered`: ACK confirmado.
- `expired`: TTL del mensaje vencido.

## Garantías

- Persistencia antes de `accepted`.
- Entrega al menos una vez.
- Idempotencia por `clientMessageId`.
- Sin orden global contractual.
- Presencia y suscripciones representan estado actual.
- Entregas `user` pueden reasignarse tras falla/reconexión.
- `connection`, `topic` y `tenant` no se reasignan a sesiones futuras.

## Compatibilidad

El servidor detecta v2 por `v=2` y presencia de `op`. Cuando
`realtimeEnabled=false`, continúa disponible el protocolo heredado. JWT también
puede activarse sin v2, pero la identidad seguirá derivándose del token.

## Autorizaciones de acceso

`login.authorization` está reservado para autoridades internas como Gator
Security. El móvil recibe y confirma el mensaje, pero aprueba o rechaza por
HTTPS ante la autoridad que creó el desafío; una decisión publicada por
WebSocket nunca es válida.

```json
{"type":"login.authorization","data":{"authorizationId":"uuid","application":"Gator ERM","expiresAt":"2026-07-30T18:30:00Z"}}
```

La autoridad usa `ws_publish_system`, que conserva idempotencia y entrega a
todas las conexiones activas del sujeto OIDC.
