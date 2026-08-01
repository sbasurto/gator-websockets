# Despliegue

Esta guía describe un despliegue reproducible con varios nodos, Keycloak,
PostgreSQL, systemd, HAProxy y certificados Let's Encrypt. Los nombres y rutas
de Artemisa/Hera documentan la instalación vigente; deben sustituirse en otro
entorno.

## Prerrequisitos

- Java 21.
- PostgreSQL accesible por todos los nodos.
- Keycloak con un realm y JWKS accesible.
- HAProxy en cada host de entrada.
- Certificado TLS válido por hostname público.
- Usuario y grupo `tomcat` para ejecutar Java.
- Acceso privado entre balanceadores, nodos y PostgreSQL.
- `psql`, `logger`, systemd y Certbot para las tareas operativas.

No se deben guardar secretos, tokens, archivos PKCS#12 ni configuraciones
reales en Git.

## 1. Compilar y verificar

Desde la raíz:

```bash
./gradlew clean check jar
```

El artefacto se genera en `dist/gator-websockets.jar`. Los self-checks cubren
framing WebSocket, HPKE, cifrado bidireccional y validación JWT.

Clientes:

```bash
./gradlew -p clients/android check
cd clients/javascript && npm test
cd clients/ios && swift test
```

Swift requiere Xcode/CryptoKit; en Linux puede verificarse al menos el parser
con `swiftc -frontend -parse`.

## 2. Preparar PostgreSQL

Aplicar el esquema con un usuario autorizado:

```bash
psql -v ON_ERROR_STOP=1 -d application_database -f deploy/realtime.sql
```

El script es idempotente para tablas, índices, vistas y función de limpieza.

Cada nodo necesita una configuración `gator-lib` cuyo archivo de base incluya:

```json
{
  "tipoDb": "pgsql",
  "servidor": "database.internal",
  "puerto": "5432",
  "sid": "application_database",
  "usuario": "application_user",
  "password": "secret-outside-git",
  "ssl": "true"
}
```

En la instalación actual PostgreSQL escucha en `localhost,10.100.0.1` y
`pg_hba.conf` permite exclusivamente a Hera (`10.100.0.33/32`) acceder a
`db_gatormail` como `w3apps` mediante SCRAM. Esta base es la autoridad de
identidad compartida y contiene la coordinación WebSocket. No debe abrirse
`5432` a Internet.

## 3. Configurar Keycloak

Configuración actual:

| Elemento | Valor |
| --- | --- |
| Realm | `gator` |
| Cliente/audiencia del servidor | `gator-websockets` |
| Cliente móvil existente | `gator-mobile` |
| Role para difusión | `broadcast:tenant` |

El access token debe incluir `gator-websockets` en `aud`. Para ello se usa un
audience mapper en los clientes que obtienen tokens para WebSocket. La cuenta
de servicio `gator-websockets` permite E2E automatizado sin usar credenciales
de una persona.

El servidor no necesita el client secret para validar tokens: solo requiere
issuer, audience y JWKS. El secret se usa únicamente para flujos confidenciales
o pruebas de cuenta de servicio.

## 4. Crear `websocket.properties`

Ejemplo de producción sin valores privados:

```properties
port=12381
withDebug=false
withSSL=true
truststore=server-internal.p12
alias=server
passphrase=outside-git
gappConfigFile=indexApplication
hpkeMaxConnectionsPerKey=500
hpkeMaxKeyAgeSeconds=86400
allowedOrigins=https://app.example.com
maxConnections=1000
handshakeTimeoutSeconds=30
authenticationTimeoutSeconds=30
idleTimeoutSeconds=300
realtimeEnabled=true
realtimeDbConfigFile=pg_mobile_authorization
tenantId=example
applicationId=gator
serverHeartbeatSeconds=10
serverLeaseSeconds=30
fcmProjectId=
jwtIssuer=https://identity.example.com/realms/gator
jwtAudience=gator-websockets
jwtJwksUri=https://identity.example.com/realms/gator/protocol/openid-connect/certs
jwtClockSkewSeconds=30
jwtJwksCacheSeconds=300
```

Reglas relevantes:

- `serverLeaseSeconds` debe ser mayor que `serverHeartbeatSeconds`.
- `jwtAudience` es obligatorio cuando existe `jwtIssuer`.
- `jwtJwksUri` debe usar HTTPS; HTTP solo se acepta para loopback.
- `GATOR_WS_PORT` reemplaza `port`, permitiendo varias instancias con un solo
  archivo.
- Si `jwtIssuer` está vacío se conserva la autenticación heredada; producción
  debe configurarlo.
- `allowedOrigins` vacío rechaza navegadores pero permite clientes sin
  `Origin`; `*` debe reservarse para desarrollo.
- `fcmProjectId` vacío deshabilita FCM sin afectar WebSocket. En producción se
  recomienda definirlo mediante `GATOR_FCM_PROJECT_ID`.

Para Android, instalar la cuenta de servicio fuera de Git y crear el archivo
de entorno leído por systemd:

```text
/etc/gator/fcm-service-account.json
/etc/gator/gator-websockets.env
```

`gator-websockets.env` contiene únicamente configuración del entorno:

```properties
GOOGLE_APPLICATION_CREDENTIALS=/etc/gator/fcm-service-account.json
GATOR_FCM_PROJECT_ID=example-firebase-project
```

Ambos archivos deben pertenecer al usuario del servicio y tener modo `0600`.

## 5. Instalar el nodo

Rutas actuales:

```text
/home/gapps/softgator/tomcat-11-production/privlib/bin/gator-websockets.jar
/home/gapps/softgator/tomcat-11-production/conf/websocket.properties
/etc/systemd/system/gator-websockets@.service
```

Instalación:

```bash
install -o root -g tomcat -m 640 dist/gator-websockets.jar \
  /home/gapps/softgator/tomcat-11-production/privlib/bin/gator-websockets.jar
install -o root -g root -m 644 deploy/gator-websockets@.service \
  /etc/systemd/system/gator-websockets@.service
systemctl daemon-reload
systemctl enable --now gator-websockets@12381.service
```

Artemisa ejecuta `@12381` y `@12382`; Hera ejecuta `@12381`. Las tres instancias
coordinan mediante `pg_mobile_authorization` en `db_gatormail`, con
`tenantId=soft-gator` y `applicationId=gator`. Antes de
reemplazar JAR, configuración o unidad, crear un respaldo fechado en el mismo
directorio.

## 6. Instalar HAProxy

Artemisa usa `deploy/haproxy.cfg`; Hera usa `deploy/haproxy-hera.cfg`.

```bash
install -o root -g root -m 644 deploy/haproxy.cfg /etc/haproxy/haproxy.cfg
haproxy -c -f /etc/haproxy/haproxy.cfg
systemctl enable --now haproxy.service
```

Siempre ejecutar `haproxy -c` antes de recargar. Los archivos actuales terminan
TLS en el frontend y vuelven a cifrar hacia los nodos. Los puertos de backend
deben ser accesibles solo por loopback o VPN.

## 7. Certificados y renovación

HAProxy espera un PEM que contenga full chain seguido de private key:

```text
/etc/haproxy/certs/artemisa.soft-gator.com.pem
/etc/haproxy/certs/hera.soft-gator.com.pem
```

Instalar `deploy/gator-websockets-cert-renew` como hook:

```bash
install -o root -g root -m 755 deploy/gator-websockets-cert-renew \
  /etc/letsencrypt/renewal-hooks/deploy/gator-websockets
```

Certbot define `RENEWED_LINEAGE`. El hook crea el PEM con modo `0600`, valida
HAProxy y solo entonces recarga el servicio.

## 8. Mantenimiento

Instalar en un solo host para evitar trabajo duplicado:

```bash
install -o root -g root -m 755 deploy/gator-websockets-maintenance \
  /usr/local/sbin/gator-websockets-maintenance
install -o root -g root -m 644 deploy/gator-websockets-maintenance.service \
  deploy/gator-websockets-maintenance.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now gator-websockets-maintenance.timer
```

Adaptar `WS_DB_HOST`, `WS_DB_USER` y `WS_DB_NAME` en la unidad o mediante un
override de systemd cuando no se usen los valores actuales.

## 9. Validación

Comprobar, en este orden:

```bash
systemctl is-active haproxy gator-websockets@12381.service
systemctl is-enabled haproxy gator-websockets@12381.service
haproxy -c -f /etc/haproxy/haproxy.cfg
```

TLS:

```bash
openssl s_client -connect host.example.com:12380 \
  -servername host.example.com -verify_hostname host.example.com </dev/null
```

Base:

```sql
select hostname, heartbeat_at
from ws_server_instance
where heartbeat_at > clock_timestamp() - interval '30 seconds';

select * from ws_alerts;
```

El E2E JavaScript recibe el token por variable, nunca por argumento o archivo
versionado:

```bash
E2E_TOKEN='access-token' \
E2E_URLS='wss://primary:12380/,wss://secondary:12380/' \
node clients/javascript/e2e/distributed.mjs
```

Salida esperada: entrega, ACK y presencia en `true`. `distributed` indica si
las dos conexiones terminaron en nodos diferentes; dos balanceadores pueden
elegir legítimamente el mismo nodo.

## 10. Rollback

1. Restaurar JAR y configuración respaldados.
2. Restaurar el archivo HAProxy anterior.
3. Validar HAProxy antes de recargar.
4. Reiniciar las instancias Java una por una.
5. Confirmar heartbeats y `ws_alerts`.

No eliminar `deploy/realtime.sql` ni tablas con mensajes durante un rollback de
binario. La compatibilidad heredada permite desactivar `realtimeEnabled`, pero
los datos persistidos deben conservarse hasta decidir su retención.
