# Gator WebSockets

Servidor WebSocket distribuido escrito en Java 21. Implementa directamente el
handshake y los frames RFC 6455, autenticación JWT/Keycloak, cifrado HPKE y
entrega persistente coordinada por PostgreSQL.

El servidor forma parte del ecosistema open source Gator y se construye junto con `gator-lib` y `gator-lib-utils`.

## Características

- WebSocket sobre TCP o TLS.
- Una sesión aislada por conexión.
- Cifrado de aplicación HPKE con X25519, HKDF-SHA-256 y AES-256-GCM.
- Mensajes directos, difusión y eventos de conexión.
- Frames de texto, cierre, ping y pong; los frames binarios se rechazan con
  código `1003`.
- Mensajes y frames limitados a 16 MiB.
- JWT RS256 con issuer, audience, vigencia y JWKS cacheado.
- Varios nodos con presencia, suscripciones y entregas persistentes.
- Entrega al menos una vez, ACK e idempotencia por `clientMessageId`.
- Clientes oficiales para JavaScript, Android e iOS.
- Métricas, alertas, retención y failover mediante componentes nativos.
- JAR ejecutable autocontenido.

## Documentación

| Documento | Contenido |
| --- | --- |
| [Arquitectura](docs/architecture.md) | Topología, componentes, flujos, garantías y matriz de fallas. |
| [Despliegue](docs/deployment.md) | PostgreSQL, Keycloak, nodos, HAProxy, TLS y rollback. |
| [Operación](docs/operations.md) | Métricas, alertas, limpieza, logs y runbooks. |
| [Seguridad](docs/security.md) | JWT, HPKE, secretos, red, hardening y riesgos. |
| [Protocolo v2](docs/protocol-v2.md) | Contratos JSON, destinos, ACK, presencia y errores. |
| [Clientes](clients/README.md) | JavaScript, Android e iOS. |

## Requisitos

- JDK 21.
- Acceso a Maven Central durante la primera compilación.
- PostgreSQL y la configuración de base de datos esperada por `gator-lib`.
- Los repositorios `gator-lib` y `gator-lib-utils` en directorios hermanos:

  ```text
  javaProjects/
  ├── gator-lib-utils/
  ├── gator-lib/
  └── gator-websockets/
  ```

## Compilación

```bash
./gradlew clean build
```

Con `gator-lib` en otro directorio:

```bash
./gradlew clean build -PgatorLibDir=/ruta/gator-lib
```

El build ejecuta self-checks del framing WebSocket, el vector oficial de RFC
9180 y el cifrado bidireccional AES-256-GCM. También genera:

```text
dist/gator-websockets.jar
```

## Configuración

El servidor lee `websocket.properties` desde el directorio definido por `GappFiles.CONF_DIR`, perteneciente a `gator-lib-utils`.

Configuración mínima:

```properties
port=8080
withDebug=false
withSSL=false
gappConfigFile=database.properties
hpkeMaxConnectionsPerKey=500
hpkeMaxKeyAgeSeconds=86400
allowedOrigins=https://app.example.com,https://admin.example.com
maxConnections=1000
handshakeTimeoutSeconds=30
authenticationTimeoutSeconds=30
idleTimeoutSeconds=300
realtimeEnabled=false
realtimeDbConfigFile=indexRealtime
tenantId=default
applicationId=gator
serverHeartbeatSeconds=10
serverLeaseSeconds=30
jwtIssuer=https://auth.example.com/realms/gator
jwtAudience=gator-websockets
jwtJwksUri=https://auth.example.com/realms/gator/protocol/openid-connect/certs
jwtClockSkewSeconds=30
jwtJwksCacheSeconds=300
```

El protocolo distribuido v2 es opcional durante la migración. Al habilitarlo,
todos los servidores deben apuntar a la misma base preparada con
`deploy/realtime.sql`. Consulta la [arquitectura](docs/architecture.md) y el
[contrato v2](docs/protocol-v2.md).

`allowedOrigins` compara orígenes completos de forma exacta. Si queda vacío,
rechaza conexiones de navegador pero permite clientes sin `Origin`; `*` acepta
cualquier origen y solo debe utilizarse en desarrollo.

`maxConnections` limita las sesiones simultáneas. Los tres timeouts controlan,
respectivamente, cuánto puede tardar el handshake, cuánto tiempo tiene el
cliente para autenticarse y cuánto puede permanecer inactiva una sesión.
`idleTimeoutSeconds=0` deshabilita únicamente el timeout de inactividad.

Cuando TLS está habilitado también se requieren:

```properties
withSSL=true
truststore=server.p12
alias=server
passphrase=change-me
```

El truststore debe estar en el mismo directorio de configuración. No se deben versionar contraseñas, certificados ni configuraciones reales.

## Ejecución

```bash
java -jar dist/gator-websockets.jar
```

También puede ejecutarse desde Gradle:

```bash
./gradlew run
```

El servidor escucha en la ruta WebSocket `/` y mantiene el proceso activo hasta que se detiene externamente.

## Protocolo de aplicación

Después del handshake, el servidor entrega la clave pública X25519 de la
generación asignada a la conexión. Una generación admite de forma
predeterminada 500 conexiones o 24 horas, lo que ocurra primero. Las conexiones
existentes conservan su generación hasta cerrarse.

Antes de autenticarse solo se acepta:

| Tipo | Propósito |
| --- | --- |
| `askkey` | Solicita la clave pública del servidor. |
| `authenticateme` | Envía las credenciales dentro del primer envelope HPKE. |

Una sesión autenticada también acepta:

| Tipo | Propósito |
| --- | --- |
| `getuserlist` | Consulta los usuarios conectados. |
| `message` | Envía un mensaje a destinatarios concretos o a todos. |
| `event` | Difunde un evento. |

### Oferta de clave

`askauth` contiene la clave pública X25519 cruda de 32 bytes codificada como
Base64URL sin padding:

```json
{
  "type": "askauth",
  "keyForAuth": "...",
  "data": {
    "version": "1",
    "keyId": "...",
    "suite": "DHKEM_X25519_HKDF_SHA256_AES_256_GCM"
  }
}
```

### Inicio de sesión HPKE

La suite sigue el modo base de RFC 9180:

```text
KEM  = DHKEM(X25519, HKDF-SHA-256)
KDF  = HKDF-SHA-256
AEAD = AES-256-GCM
info = UTF-8("gator-websockets-v1")
```

El primer envelope usa la encapsulación HPKE y la secuencia cero:

```json
{
  "version": 1,
  "keyId": "...",
  "encapsulation": "...",
  "sequence": 0,
  "ciphertext": "..."
}
```

`encapsulation` y `ciphertext` usan Base64URL sin padding. El AAD inicial es:

```text
gator-ws-v1|<keyId>|hpke|0
```

El texto cifrado contiene el mensaje de autenticación completo:

```json
{
  "type": "authenticateme",
  "message": "access-token-jwt"
}
```

### Cifrado bidireccional

Después de abrir el primer envelope, cliente y servidor usan `Export` de RFC
9180 para obtener 44 bytes por dirección:

```text
gator-ws-v1/client-to-server → 32 bytes de clave + 12 de nonce base
gator-ws-v1/server-to-client → 32 bytes de clave + 12 de nonce base
```

Cada dirección inicia su propia secuencia en cero. El nonce se calcula como
`base_nonce XOR I2OSP(sequence, 12)` y el AAD es, según la dirección:

```text
gator-ws-v1|<keyId>|client-to-server|<sequence>
gator-ws-v1|<keyId>|server-to-client|<sequence>
```

Los envelopes posteriores omiten `encapsulation`:

```json
{
  "version": 1,
  "keyId": "...",
  "sequence": 0,
  "ciphertext": "..."
}
```

Las secuencias deben recibirse en orden. Un valor repetido, adelantado o un tag
GCM inválido cierra la conexión.

## Clientes oficiales

El directorio `clients/` incluye clientes compatibles para navegador,
Android e iOS. Cada cliente conserva el mismo formato HPKE, claves direccionales
y control de secuencia descritos arriba; consulta `clients/README.md` para sus
requisitos y ejemplos.

El modelo JSON principal se encuentra en `GatorWSMessage` y utiliza, entre otros, los campos `type`, `message`, `data`, `usuarios` y `destinatarios`.

## Integración con PostgreSQL

Con `jwtIssuer` configurado, el servidor autentica con JWT y obtiene la
identidad del claim `sub`. Sin esa configuración conserva temporalmente la
autenticación heredada mediante `app_fn_authenticate_ws`.

La aplicación espera que la capa de base de datos proporcione estos procedimientos almacenados:

- `app_fn_authenticate_ws`
- `app_fn_get_usuarios_ws`
- `app_fn_send_message_ws`

Las claves criptográficas ya no se consultan en PostgreSQL. Las tres funciones
restantes pertenecen a la integración de usuarios y mensajes de cada
aplicación; reciben y devuelven los objetos JSON descritos por este protocolo.

## Estructura

```text
src/gator/websockets/
├── frames/       # Lectura y escritura de frames RFC 6455
├── handler/      # Handshake, paquetes y protocolo de mensajes
├── helpers/      # Configuración y seguridad
└── server/       # Socket servidor y sesiones de clientes
clients/
├── javascript/   # Navegadores
├── android/      # Android 8+
└── ios/          # iOS 13+
```

## Seguridad

- En producción debe usarse `wss://`: TLS autentica la oferta de clave HPKE e
  impide que un intermediario la sustituya.
- El handshake rechaza cualquier `Origin` que no figure en `allowedOrigins`.
- Java conserva su selección segura de protocolos TLS habilitados.
- Las claves privadas permanecen en memoria y nunca se envían al cliente ni a
  PostgreSQL.
- Las credenciales, claves, nonces y payloads no se escriben en logs.
- HPKE y AES-GCM agregan confidencialidad, integridad y rechazo de mensajes
  repetidos a nivel de aplicación.
- El handshake está limitado a 16 KiB; handshake, autenticación e inactividad
  tienen límites configurables.
- Este proyecto todavía no ha recibido una auditoría de seguridad independiente.

## Limitaciones conocidas

- PostgreSQL y Keycloak siguen siendo dependencias únicas en Artemisa.
- La aplicación cliente debe cambiar al endpoint secundario; las librerías no
  seleccionan automáticamente otra URL.
- TLS hacia backends cifra el tráfico pero actualmente usa `verify none` dentro
  de la VPN.

## Contribuciones

Antes de enviar cambios, ejecuta:

```bash
./gradlew clean build
```

Mantén los cambios pequeños, incluye una verificación ejecutable para lógica no trivial y evita agregar secretos o rutas privadas al repositorio.

## Licencia

El proyecto se distribuye bajo GNU General Public License, versión 3 o posterior. Consulta `LICENSE` y `NOTICE`.
