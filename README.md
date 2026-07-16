# Gator WebSockets

Servidor WebSocket ligero escrito en Java 21. Implementa directamente el handshake y los frames definidos por RFC 6455, autenticación respaldada por PostgreSQL, mensajería directa y difusión de eventos.

El servidor forma parte del ecosistema open source Gator y se construye junto con `gator-lib` y `gator-lib-utils`.

## Características

- WebSocket sobre TCP o TLS.
- Una sesión aislada por conexión.
- Autenticación mediante claves RSA y sesión AES-CBC.
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

El build ejecuta un self-check del framing WebSocket y genera:

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

Después del handshake, el servidor entrega la clave pública asociada con la conexión. Antes de autenticarse solo acepta:

| Tipo | Propósito |
| --- | --- |
| `askkey` | Solicita la clave pública del servidor. |
| `authenticateme` | Envía las credenciales cifradas y la clave pública del cliente. |

Una sesión autenticada también acepta:

| Tipo | Propósito |
| --- | --- |
| `askkeytouse` | Solicita la clave AES de la sesión cifrada con la clave pública del cliente. |
| `getuserlist` | Consulta los usuarios conectados. |
| `message` | Envía un mensaje a destinatarios concretos o a todos. |
| `event` | Difunde un evento. |

Los mensajes cifrados utilizan el formato:

```text
<payload AES>::@@::<IV cifrado con RSA>
```

El modelo JSON principal se encuentra en `GatorWSMessage` y utiliza, entre otros, los campos `type`, `message`, `data`, `usuarios` y `destinatarios`.

## Integración con PostgreSQL

La aplicación espera que la capa de base de datos proporcione estos procedimientos almacenados:

- `app_fn_get_private_key`
- `app_fn_get_pub_key`
- `app_fn_authenticate_ws`
- `app_fn_get_usuarios_ws`
- `app_fn_send_message_ws`

Sus contratos y el esquema SQL todavía deben incorporarse al repositorio antes de la publicación pública.

## Estructura

```text
src/gator/websockets/
├── frames/       # Lectura y escritura de frames RFC 6455
├── handler/      # Handshake, paquetes y protocolo de mensajes
├── helpers/      # Configuración y seguridad
└── server/       # Socket servidor y sesiones de clientes
```

## Seguridad

- Se recomienda habilitar TLS en cualquier entorno no aislado.
- Java conserva su selección segura de protocolos TLS habilitados.
- Las credenciales, claves, IV y payloads no se escriben en logs.
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
