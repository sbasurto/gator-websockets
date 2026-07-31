# Migración de autorización móvil — 2026-07-31

## Objetivo

Centralizar presencia WebSocket y autorizaciones de acceso en `db_gatormail`,
sin cambiar el proveedor existente de correo/SMS.

## Cambios aplicados

- `gator-websockets/deploy/realtime.sql` se aplicó transaccionalmente mediante
  PgBouncer `6432`.
- Se aplicó el changelog Mail para crear las tablas y funciones móviles.
- Se otorgó únicamente `REFERENCES` sobre `app_usuarios` a `w3apps`, requerido
  por las llaves foráneas. La tabla permanece propiedad de `postgres`.
- Se creó `pg_mobile_authorization` en Artemisa y Hera. Apunta a
  `db_gatormail`; Hera usa la dirección VPN de Artemisa.
- Los tres nodos usan `tenantId=soft-gator` y `applicationId=gator`.
- Hera se migró primero; Artemisa después en orden `12381`, `12382`.

## Hallazgo y corrección

El JAR anterior ignoraba `GATOR_WS_PORT` y al reiniciarse intentaba escuchar en
`12380`, ocupado por HAProxy. El proceso terminaba sin fallo de systemd. Se
instaló el JAR validado que escucha en el puerto de cada instancia. Artemisa no
se modificó hasta observar un heartbeat sano de Hera.

## Validación

- Hera: una instancia sana en `db_gatormail`.
- Artemisa: dos instancias sanas en `db_gatormail`.
- HAProxy activo en ambos hosts y certificados TLS válidos en `12380`.
- `g-sec` responde `401` sin Bearer token en
  `/g-sec/api/mobile/authorizations`.
- Gator Mail responde mediante su redirección de autenticación normal.

## Respaldos y rollback

Los respaldos permanecen junto a sus archivos originales:

- `websocket.properties.before-mobile-auth-20260731-0853`
- `gator-websockets.jar.before-mobile-auth-20260731-0900` en Hera
- `gator-websockets.jar.before-mobile-auth-20260731-0901` en Artemisa

Para rollback, restaurar primero `websocket.properties`, después el JAR y
reiniciar una sola instancia; validar HAProxy y heartbeat antes de continuar
con la siguiente.
