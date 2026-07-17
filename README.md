# Gator WebSockets

Servidor WebSocket ligero escrito en Java 21. Implementa directamente el handshake y los frames definidos por RFC 6455, autenticación respaldada por PostgreSQL, mensajería directa y difusión de eventos.

El servidor forma parte del ecosistema open source Gator y se construye junto con `gator-lib` y `gator-lib-utils`.

## Características

- WebSocket sobre TCP o TLS.
- Una sesión aislada por conexión.
- Cifrado de aplicación HPKE con X25519, HKDF-SHA-256 y AES-256-GCM.
- Mensajes directos, difusión y eventos de conexión.
- Frames de texto, cierre, ping y pong.
- Mensajes y frames limitados a 16 MiB.
- JAR ejecutable autocontenido.

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
```

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
  "message": "passphrase",
  "data": { "usuario": "usuario-id" }
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

El modelo JSON principal se encuentra en `GatorWSMessage` y utiliza, entre otros, los campos `type`, `message`, `data`, `usuarios` y `destinatarios`.

## Integración con PostgreSQL

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
```

## Seguridad

- En producción debe usarse `wss://`: TLS autentica la oferta de clave HPKE e
  impide que un intermediario la sustituya.
- Java conserva su selección segura de protocolos TLS habilitados.
- Las claves privadas permanecen en memoria y nunca se envían al cliente ni a
  PostgreSQL.
- Las credenciales, claves, nonces y payloads no se escriben en logs.
- HPKE y AES-GCM agregan confidencialidad, integridad y rechazo de mensajes
  repetidos a nivel de aplicación.
- El handshake está limitado a 16 KiB y vence después de 30 segundos.
- Este proyecto todavía no ha recibido una auditoría de seguridad independiente.

## Limitaciones conocidas

- Incluir el esquema y los procedimientos de PostgreSQL.
- Agregar configuraciones de ejemplo independientes de infraestructura privada.
- Añadir pruebas de interoperabilidad con clientes WebSocket.
- Definir una política configurable de orígenes permitidos.

## Contribuciones

Antes de enviar cambios, ejecuta:

```bash
./gradlew clean build
```

Mantén los cambios pequeños, incluye una verificación ejecutable para lógica no trivial y evita agregar secretos o rutas privadas al repositorio.

## Licencia

El proyecto se distribuye bajo GNU General Public License, versión 3 o posterior. Consulta `LICENSE` y `NOTICE`.
