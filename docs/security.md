# Seguridad

## Modelo de confianza

La solución usa capas complementarias:

1. TLS autentica el endpoint público y protege el transporte hasta HAProxy.
2. HAProxy abre TLS hacia el nodo interno.
3. HPKE cifra y autentica cada mensaje de aplicación entre cliente y nodo.
4. Keycloak autentica la identidad mediante JWT firmado.
5. PostgreSQL aplica persistencia, aislamiento lógico e idempotencia.

HPKE no sustituye TLS: sin TLS, un intermediario podría reemplazar la oferta de
clave inicial. TLS interno usa `verify none`; por ello la VPN y el control de
los hosts son parte de la frontera de confianza.

## Validación JWT

El verificador usa únicamente Java estándar y Gson ya instalado. Reglas:

- tamaño máximo del token: 16 KiB;
- exactamente tres segmentos compactos;
- algoritmo permitido: RS256;
- `kid` obligatorio;
- claves RSA de al menos 2048 bits;
- JWKS máximo de 1 MiB;
- HTTPS obligatorio, salvo HTTP loopback;
- issuer exacto;
- audiencia como string o arreglo;
- `exp`, `nbf` y skew de reloj;
- `sub` obligatorio;
- cache JWKS y refresh limitado ante `kid` desconocido.

La configuración de producción usa 30 segundos de skew y 300 segundos de
cache. Una rotación de clave se descubre al encontrar un `kid` desconocido,
limitando refresh repetido a uno cada 5 segundos.

## Autorización

| Operación | Requisito |
| --- | --- |
| `publish` | `messages:send`. |
| `tenant` broadcast | `messages:send` y `broadcast:tenant`. |
| `subscribe`, `unsubscribe`, `ack`, `presence` | Sesión autenticada. |

Tenant, aplicación, usuario, conexión y servidor salen de la sesión; no se
aceptan del payload. Los tópicos se canonicalizan con tenant y aplicación.

## Cifrado de aplicación

Suite:

```text
DHKEM(X25519, HKDF-SHA-256)
HKDF-SHA-256
AES-256-GCM
```

Cada dirección tiene clave, nonce base y contador propios. Los contadores deben
llegar exactamente en orden; replay, salto de secuencia o tag inválido cierra
la conexión. Las generaciones de clave rotan por edad o número de conexiones,
sin interrumpir conexiones existentes.

## Credenciales y secretos

Fuera de Git:

- passwords de PostgreSQL;
- client secrets de Keycloak;
- access y refresh tokens;
- `websocket.properties` real;
- archivos PKCS#12;
- claves privadas Let's Encrypt;
- PEM combinado de HAProxy.

Permisos recomendados:

| Archivo | Propietario | Modo |
| --- | --- | --- |
| `websocket.properties` | `root:tomcat` | `0640` |
| PKCS#12 interno | `root:tomcat` | `0640` |
| PEM de HAProxy | `root:root` | `0600` |
| unidad systemd | `root:root` | `0644` |

Los tokens se pasan a pruebas por variable de entorno de un proceso efímero y
se eliminan al terminar. No deben incluirse en la línea de comandos, logs,
capturas ni incidencias.

## Red y base de datos

- `12380` es el único puerto público WebSocket.
- `12381` y `12382` deben limitarse a loopback/VPN.
- PostgreSQL `5432` debe limitarse a hosts exactos y autenticación SCRAM.
- JWKS interno puede usar loopback; entre hosts debe usar HTTPS.
- HAProxy valida configuración antes de cada reload.

La instalación actual permite en PostgreSQL únicamente
`<secondary-private-ip>/32 → application_database/application_user` por red. El cifrado PostgreSQL debe
habilitarse si el enlace deja de estar confinado a la VPN.

## Hardening del proceso

La unidad systemd usa usuario sin login y aplica:

- `NoNewPrivileges=true`;
- `PrivateTmp=true`;
- `ProtectSystem=full`;
- protección de control groups y parámetros del kernel;
- bloqueo de SUID/SGID;
- reinicio automático ante falla.

## Logging seguro

No registrar:

- JWT completo o claims innecesarios;
- passwords o client secrets;
- claves, nonces o material HPKE;
- payloads de usuario;
- contenido de archivos de configuración privados.

Se permiten identificadores técnicos (`serverId`, `connectionId`, `messageId`)
cuando sean necesarios para correlación.

## Riesgos conocidos

- PostgreSQL es un punto único de falla.
- Keycloak y su almacenamiento permanecen en el nodo primario.
- TLS interno cifra pero no verifica certificado de backend.
- Los clientes no cambian automáticamente de endpoint.
- La deduplicación de cliente guarda 1024 IDs por sesión; una repetición más
  antigua puede llegar a la aplicación.
- No existe auditoría independiente de seguridad.

## Checklist antes de producción

- [x] `jwtIssuer`, `jwtAudience` y JWKS correctos.
- [x] `withDebug=false`.
- [x] `allowedOrigins` explícito y sin `*`.
- [x] TLS público válido en ambos endpoints.
- [ ] Puertos backend y PostgreSQL no públicos.
- [ ] Permisos de secretos revisados.
- [x] Tres heartbeats y cero `ws_alerts`.
- [x] E2E JWT con entrega y ACK.
- [x] Failover probado con restauración automática.
- [ ] Backups de PostgreSQL y Keycloak verificados.
- [ ] Plan de rotación y revocación de credenciales disponible.

Los controles marcados tienen evidencia en la
[liberación del 26 de julio de 2026](operations.md#evidencia-de-liberación-del-26-de-julio-de-2026).
El 28 de julio de 2026 también se reconfirmaron en los nodos primario y secundario la
configuración JWT/JWKS, `withDebug=false`, los orígenes explícitos y permisos
`0640` para `websocket.properties`. Los controles abiertos continúan siendo
requisitos pendientes y no quedan cubiertos por esa liberación.
