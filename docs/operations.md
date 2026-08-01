# Operación y runbook

## Inventario actual

| Host | Servicio | Función |
| --- | --- | --- |
| Artemisa | `haproxy.service` | Entrada pública primaria `:12380`. |
| Artemisa | `gator-websockets@12381.service` | Nodo WebSocket. |
| Artemisa | `gator-websockets@12382.service` | Nodo WebSocket. |
| Artemisa | `gator-websockets-maintenance.timer` | Limpieza, métricas y alertas. |
| Artemisa | PostgreSQL 18 | Persistencia y coordinación. |
| Artemisa | Keycloak | Emisión de JWT y JWKS. |
| Hera | `haproxy.service` | Entrada pública secundaria `:12380`. |
| Hera | `gator-websockets@12381.service` | Nodo WebSocket. |

## Comprobación rápida

Servicios:

```bash
systemctl is-active haproxy gator-websockets@12381.service
systemctl is-enabled haproxy gator-websockets@12381.service
```

En Artemisa agregar `gator-websockets@12382.service` y el timer. Un servicio
`active` pero no `enabled` no sobrevivirá un reinicio.

Listeners:

```bash
ss -lnt | grep -E ':(12380|12381|12382)[[:space:]]'
```

Salud distribuida:

```sql
select hostname, server_id, heartbeat_at,
       extract(epoch from clock_timestamp()-heartbeat_at)::integer age_seconds
from ws_server_instance
order by heartbeat_at desc;

select * from ws_alerts;
select * from ws_metrics;
```

En producción sana deben verse tres heartbeats recientes y ninguna alerta.

## Métricas

`ws_metrics` entrega una fila por tenant y aplicación:

| Campo | Significado |
| --- | --- |
| `active_connections` | Conexiones abiertas en nodos con heartbeat menor a 60 s. |
| `messages_5m` | Mensajes creados en los últimos 5 minutos. |
| `pending_deliveries` | Entregas `pending` o `dispatched`. |
| `retries_total` | Intentos adicionales acumulados. |
| `delivery_latency_ms_1h` | Latencia media creación-ACK en la última hora. |

El timer escribe un JSON en journald:

```bash
journalctl -t gator-websockets-metrics --since '15 minutes ago'
```

Estas métricas son operativas y simples; no sustituyen una serie temporal. Si
se necesita historial, enviar los eventos de journald al sistema central de
observabilidad.

## Alertas

`ws_alerts` genera:

| Alerta | Condición | Acción |
| --- | --- | --- |
| `no_active_server` | Ningún heartbeat en 60 s. | Revisar nodos y conectividad PostgreSQL. |
| `old_pending_delivery` | Entrega pendiente por más de 60 s. | Revisar conexión destino, nodos y reintentos. |
| `repeated_delivery_failure` | Entrega con 3 o más intentos. | Revisar logs y estado de la conexión. |
| `push_delivery_failure` | FCM rechazó definitivamente una entrega. | Revisar `last_error`; renovar el token del dispositivo si fue invalidado. |

El mantenimiento registra alertas con prioridad warning y sale con código 1:

```bash
journalctl -t gator-websockets-alert --since today
systemctl status gator-websockets-maintenance.service
```

El timer vuelve a ejecutar la tarea en el siguiente minuto; un fallo no detiene
los nodos.

## Retención

Valores predeterminados de `ws_cleanup()`:

- mensajes: 30 días;
- conexiones cerradas: 7 días;
- instancias sin heartbeat: 7 días;
- entregas con TTL vencido: estado `expired`.

Ejecución manual y personalizada:

```sql
select ws_cleanup();
select ws_cleanup(interval '14 days', interval '3 days', interval '3 days');
```

La función despeja referencias antes de borrar conexiones o servidores. Las
entregas se eliminan por cascada cuando se elimina su mensaje.

## Logs

```bash
journalctl -u gator-websockets@12381.service --since '30 minutes ago'
journalctl -u haproxy.service --since '30 minutes ago'
journalctl -u gator-websockets-maintenance.service -n 50
```

Buscar fallas de coordinación, JWT, TLS y base sin copiar tokens ni payloads a
incidentes. Los mensajes del verificador solo describen la causa del rechazo;
no incluyen el JWT.

FCM conserva sólo código HTTP o clase de excepción, nunca el token ni la llave:

```sql
select status, attempts, last_error, available_at
from ws_push_delivery
where status in ('pending','failed')
order by push_delivery_id desc;
```

Los errores `400`, `404` y `UNREGISTERED` se cierran sin reintento. Los fallos
transitorios reintentan con espera exponencial, hasta ocho intentos.

## Runbook: nodo Java caído

1. Confirmar que HAProxy lo marcó DOWN.
2. Revisar `journalctl` del nodo.
3. Confirmar que al menos otro nodo y PostgreSQL siguen activos.
4. Reiniciar el nodo.
5. Esperar dos health checks y confirmar heartbeat nuevo.
6. Consultar `ws_alerts` y entregas con reintentos.

```bash
systemctl restart gator-websockets@12381.service
systemctl is-active gator-websockets@12381.service
```

## Runbook: balanceador primario caído

1. La aplicación debe cambiar a `wss://hera.soft-gator.com:12380/`.
2. Confirmar TLS y estado de HAProxy en Hera.
3. Reparar o reiniciar HAProxy de Artemisa.
4. Validar configuración antes de recargar.

Las conexiones existentes al primario se pierden y deben recrearse. La entrega
pendiente para usuario se reasigna después del lease y la reconexión.

## Runbook: PostgreSQL inaccesible

Síntomas: heartbeats detenidos, publicaciones `request_failed`, presencia y ACK
fallando. Acciones:

1. No reiniciar simultáneamente todos los nodos.
2. Revisar PostgreSQL, listener `5432`, VPN y `pg_hba.conf`.
3. Confirmar que la credencial SCRAM sigue coincidiendo.
4. Restaurar PostgreSQL.
5. Verificar que los coordinadores reconectan automáticamente cada segundo.
6. Revisar pendientes, reintentos y alertas.

Actualmente no hay réplica PostgreSQL automática; una pérdida definitiva de la
base requiere restauración desde backup.

## Runbook: rotación de certificado

Certbot ejecuta el hook de despliegue, reconstruye el PEM y recarga HAProxy.
Para verificar:

```bash
certbot certificates
haproxy -c -f /etc/haproxy/haproxy.cfg
openssl s_client -connect host:12380 -servername host \
  -verify_hostname host </dev/null
```

No reiniciar los nodos Java por una renovación del certificado público de
HAProxy.

## Prueba de failover

La prueba controlada de la capa WebSocket es:

1. obtener el JWT antes del corte;
2. detener HAProxy y nodos Java de Artemisa;
3. esperar 6 segundos para los health checks de Hera;
4. conectar dos clientes al endpoint de Hera;
5. publicar, recibir y confirmar un mensaje;
6. restaurar servicios en un bloque `finally`;
7. confirmar tres heartbeats y cero alertas.

No detener PostgreSQL o Keycloak en esta prueba: esa falla aún no está cubierta.

## Evidencia de liberación del 26 de julio de 2026

- TLS válido en ambos endpoints, `Verify return code: 0`.
- Tres nodos con heartbeat y cero alertas.
- E2E público entre Artemisa y Hera: JWT, entrega, ACK y presencia correctos.
- E2E directo entre hosts: `distributed=true`.
- Corte de HAProxy y ambos nodos de Artemisa: Hera continuó después de 6 s.
- Temporales que contenían configuración, certificado o credenciales eliminados.
