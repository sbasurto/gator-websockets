# Arquitectura distribuida

## Objetivo

Gator WebSockets mantiene conexiones de navegador, Android e iOS y entrega
mensajes en tiempo real aunque una instancia Java deje de responder. Los nodos
no comparten memoria: coordinan presencia, suscripciones y entregas mediante
PostgreSQL.

La arquitectura actual ofrece redundancia de la capa WebSocket en dos hosts.
PostgreSQL y Keycloak continúan en Artemisa; por ello la pérdida completa de
Artemisa todavía no es una falla cubierta de extremo a extremo.

## Topología

```text
                         ┌──────────────────────┐
                         │ Keycloak / realm    │
                         │ gator               │
                         └──────────┬───────────┘
                                    │ JWT + JWKS
                                    │
┌─────────────────────┐     ┌───────▼────────┐
│ JavaScript          │     │ Clientes       │
│ Android             ├────►│ WSS + HPKE     │
│ iOS                 │     └───┬────────┬───┘
└─────────────────────┘         │        │
                                │        │
                 primaria       │        │ secundaria
                 Artemisa       │        │ Hera
             ┌──────────────────▼┐      ┌▼──────────────────┐
             │ HAProxy :12380    │      │ HAProxy :12380    │
             │ termina TLS       │      │ termina TLS       │
             └───────┬───────────┘      └──────────┬────────┘
                     │ TLS                            │ TLS
          ┌──────────┼────────────────────────────────┤
          │          │                                │
     ┌────▼─────┐ ┌──▼───────┐                  ┌────▼─────┐
     │ Artemisa │ │ Artemisa │                  │ Hera     │
     │ :12381   │ │ :12382   │                  │ :12381   │
     └────┬─────┘ └──┬───────┘                  └────┬─────┘
          │          │                               │
          └──────────┴──────── PostgreSQL ────────────┘
                   mensajes, entregas, conexiones,
                   suscripciones y heartbeats
```

Endpoints públicos:

- Primario: `wss://artemisa.soft-gator.com:12380/`.
- Secundario: `wss://hera.soft-gator.com:12380/`.

Los clientes oficiales reciben una URL por instancia. La aplicación que los
integra debe crear una nueva instancia contra el endpoint secundario cuando el
primario no responda; no existe cambio automático de URL dentro de la librería.

## Componentes

### Clientes

Los tres clientes realizan las mismas tareas:

1. Abren WebSocket sobre TLS.
2. Reciben la oferta de clave X25519.
3. Crean el contexto HPKE.
4. Envían el access token dentro del primer envelope cifrado.
5. Cifran todos los mensajes posteriores con AES-256-GCM y secuencia estricta.
6. Deduplican entregas v2 por `messageId`.
7. Envían ACK después de descifrar y validar el mensaje.

La obtención, renovación y almacenamiento del access token corresponde a la
aplicación que integra el cliente.

### Keycloak

Keycloak emite access tokens para el realm `gator`. El servidor acepta tokens
RS256 que cumplan simultáneamente:

- `iss` idéntico a `jwtIssuer`.
- `aud` contiene `jwtAudience`.
- `exp` está vigente, considerando el skew configurado.
- `nbf`, si existe, ya está vigente.
- `sub` existe y no está vacío.
- La firma corresponde al `kid` publicado por JWKS.

`sub` se convierte en `userId`; `preferred_username` se usa como nombre visible.
Los roles de realm y del cliente cuya audiencia coincide se convierten en
scopes. Toda identidad recibe `messages:send` y `messages:receive`; la difusión
al tenant requiere además `broadcast:tenant`.

### HAProxy

Cada host expone `:12380`. HAProxy:

- termina TLS con el certificado del hostname público local;
- abre otra conexión TLS hacia el nodo elegido;
- distribuye conexiones con round-robin;
- ejecuta un TCP health check cada 2 segundos;
- retira un backend después de 2 fallos y lo reincorpora después de 2 éxitos.

La ventana normal de detección es de aproximadamente 4 a 6 segundos. La
conexión WebSocket no se migra: el cliente debe reconectarse.

Los backends usan `ssl verify none`. El tráfico sigue cifrado, pero la identidad
del certificado interno no se verifica; la red VPN entre `10.100.0.1` y
`10.100.0.33` es parte de la frontera de confianza actual.

### Nodos WebSocket

Cada proceso Java tiene un `serverId` UUID nuevo al arrancar y escucha un puerto
definido por `GATOR_WS_PORT`. Sus responsabilidades son:

- handshake y framing RFC 6455;
- negociación HPKE y secuencias criptográficas;
- validación JWT con cache JWKS compartida por todas sus conexiones;
- registro de conexión y heartbeat en PostgreSQL;
- validación de contratos v2;
- persistencia, asignación y entrega de mensajes;
- publicación de ACK, presencia y suscripciones.

Las unidades systemd reinician el proceso 5 segundos después de una falla.

### PostgreSQL

PostgreSQL es la fuente de verdad distribuida. `deploy/realtime.sql` crea:

| Objeto | Función |
| --- | --- |
| `ws_server_instance` | Identidad y heartbeat de cada proceso. |
| `ws_connection` | Sesiones autenticadas, host propietario y cierre. |
| `ws_subscription` | Tópicos activos por conexión. |
| `ws_message` | Envelope persistido e idempotencia del publicador. |
| `ws_delivery` | Estado, asignación, reintentos y ACK por conexión. |
| `ws_metrics` | Vista operativa por tenant y aplicación. |
| `ws_alerts` | Condiciones accionables de salud. |
| `ws_cleanup()` | Expiración y retención de datos antiguos. |

`LISTEN/NOTIFY` reduce la latencia de entrega. No es la fuente de verdad: cada
nodo también consulta periódicamente entregas pendientes, de modo que perder un
`NOTIFY` no pierde el mensaje.

## Flujos

### Conexión y autenticación

1. La aplicación obtiene un access token de Keycloak.
2. El cliente abre uno de los endpoints WSS.
3. HAProxy selecciona un nodo sano.
4. El nodo ofrece una clave HPKE.
5. El cliente envía `authenticateme` con el JWT dentro del envelope HPKE.
6. El nodo obtiene o reutiliza el JWKS y valida el token.
7. El nodo registra `connectionId`, `serverId`, tenant, aplicación y usuario.
8. `authsuccess.data` devuelve esos identificadores y `authentication=jwt`.

El cliente nunca puede elegir su `userId`, tenant, aplicación, conexión o
servidor mediante un `publish`.

### Publicación

1. El nodo valida scope, destino, tópicos y payload.
2. Inserta `ws_message` en una transacción.
3. Resuelve conexiones sanas para el destino.
4. Crea una fila `ws_delivery` por conexión.
5. Para usuarios offline crea una entrega sin conexión asignada.
6. Ejecuta `pg_notify` y confirma la transacción.
7. Responde `accepted` con `messageId` y `clientMessageId`.

La restricción única por tenant, aplicación, emisor y `clientMessageId` hace la
publicación idempotente. Repetir el mismo identificador devuelve el
`messageId` original.

### Entrega y ACK

1. El nodo propietario selecciona hasta 100 entregas disponibles.
2. Envía el envelope a la conexión asignada.
3. Marca la entrega `dispatched`, incrementa `attempts` y programa un nuevo
   intento en 15 segundos.
4. El cliente descifra, valida y deduplica por `messageId`.
5. El cliente envía `ack` con estado `delivered`.
6. PostgreSQL marca la entrega `delivered` y registra `delivered_at`.

Si el nodo no encuentra la conexión en memoria, reintenta en 5 segundos. La
garantía es **al menos una vez**; el ACK puede perderse y provocar una entrega
repetida.

### Suscripciones y presencia

Los tópicos se almacenan internamente con prefijo de tenant y aplicación. Esto
impide que una suscripción cruce esos límites. Presencia solo cuenta conexiones
abiertas cuyo servidor tenga heartbeat dentro del lease.

### Caída y recuperación

Al vencer `serverLeaseSeconds`:

1. Otro nodo detecta el heartbeat antiguo.
2. Cierra lógicamente las conexiones del servidor caído.
3. Las entregas de tipo `user` sin ACK vuelven a `pending` y pierden su
   asignación de conexión/servidor.
4. Cuando el usuario se reconecta, las entregas offline se asignan a la nueva
   conexión.

Los destinos `connection`, `topic` y `tenant` representan las conexiones que
existían al publicar. No se reasignan a conexiones futuras.

## Garantías y límites

| Aspecto | Garantía actual |
| --- | --- |
| Persistencia | El mensaje se confirma antes de `accepted`. |
| Entrega | Al menos una vez. |
| Idempotencia de publicación | Por `clientMessageId` y emisor. |
| Deduplicación de cliente | Últimos 1024 `messageId` por sesión. |
| Orden | Orden de selección por `delivery_id`; no hay orden global contractual. |
| Destinatarios | Máximo 100 IDs por publicación. |
| Tópicos | Máximo 100 por operación; 1 a 128 caracteres válidos. |
| Tamaño | Handshake 16 KiB; frame/mensaje 16 MiB. |
| Heartbeat | 10 s en producción. |
| Lease | 30 s en producción. |
| Failover de balanceador | Aproximadamente 4 a 6 s más reconexión del cliente. |

## Matriz de fallas

| Falla | Resultado |
| --- | --- |
| Un proceso Java | HAProxy lo retira; otros nodos continúan. |
| Ambos procesos Java de Artemisa | Hera continúa entregando después del health check. |
| HAProxy de Artemisa | El cliente debe usar el endpoint de Hera. |
| Nodo Hera | Los dos nodos de Artemisa continúan. |
| Enlace VPN | Cada balanceador conserva sus nodos locales; se pierde coordinación si también se pierde PostgreSQL. |
| Keycloak temporalmente | Sesiones existentes continúan; autenticaciones nuevas pueden usar JWKS cacheado mientras siga disponible. |
| PostgreSQL | Publicación, presencia, ACK y coordinación se interrumpen. |
| Host Artemisa completo | Hera conserva el proceso, pero pierde PostgreSQL y Keycloak; no hay HA total. |

## Siguiente nivel de disponibilidad

Para tolerar la pérdida completa de Artemisa se requiere:

1. réplica PostgreSQL en otro host;
2. failover automático de PostgreSQL y un endpoint estable de base de datos;
3. Keycloak con almacenamiento y réplicas tolerantes a host;
4. selección automática del endpoint WebSocket mediante DNS health checks,
   balanceador externo o lógica de reconexión en la aplicación.

Consulta también [deployment.md](deployment.md), [operations.md](operations.md),
[security.md](security.md) y [protocol-v2.md](protocol-v2.md).
