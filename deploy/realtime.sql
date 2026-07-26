create table if not exists ws_server_instance (
    server_id uuid primary key,
    hostname text not null,
    started_at timestamptz not null default clock_timestamp(),
    heartbeat_at timestamptz not null default clock_timestamp()
);

create table if not exists ws_connection (
    connection_id uuid primary key,
    server_id uuid not null references ws_server_instance(server_id),
    tenant_id text not null,
    application_id text not null,
    user_id text not null,
    connected_at timestamptz not null default clock_timestamp(),
    last_seen_at timestamptz not null default clock_timestamp(),
    closed_at timestamptz,
    close_reason text
);
create index if not exists ws_connection_active_user_idx
    on ws_connection (tenant_id, application_id, user_id) where closed_at is null;

create table if not exists ws_subscription (
    connection_id uuid not null references ws_connection(connection_id) on delete cascade,
    topic text not null,
    subscribed_at timestamptz not null default clock_timestamp(),
    primary key (connection_id, topic)
);
create index if not exists ws_subscription_topic_idx on ws_subscription (topic);

create table if not exists ws_message (
    message_id uuid primary key,
    client_message_id uuid not null,
    tenant_id text not null,
    application_id text not null,
    sender_user_id text not null,
    target_kind text not null check (target_kind in ('connection','user','topic','tenant')),
    envelope jsonb not null,
    created_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz,
    unique (tenant_id, application_id, sender_user_id, client_message_id)
);

create table if not exists ws_delivery (
    delivery_id bigint generated always as identity primary key,
    message_id uuid not null references ws_message(message_id) on delete cascade,
    target_user_id text,
    connection_id uuid references ws_connection(connection_id),
    server_id uuid references ws_server_instance(server_id),
    status text not null default 'pending' check (status in ('pending','dispatched','delivered','expired')),
    attempts integer not null default 0,
    available_at timestamptz not null default clock_timestamp(),
    dispatched_at timestamptz,
    delivered_at timestamptz,
    unique (message_id, connection_id)
);
create index if not exists ws_delivery_server_pending_idx
    on ws_delivery (server_id, available_at) where status = 'pending';
create index if not exists ws_delivery_offline_idx
    on ws_delivery (target_user_id) where connection_id is null and status = 'pending';

create or replace view ws_metrics as
with dimensions as (
    select tenant_id, application_id from ws_connection
    union
    select tenant_id, application_id from ws_message
)
select d.tenant_id, d.application_id,
    (select count(*) from ws_connection c join ws_server_instance s using (server_id)
      where c.tenant_id=d.tenant_id and c.application_id=d.application_id
        and c.closed_at is null and s.heartbeat_at > clock_timestamp()-interval '60 seconds') as active_connections,
    (select count(*) from ws_message m where m.tenant_id=d.tenant_id
      and m.application_id=d.application_id and m.created_at > clock_timestamp()-interval '5 minutes') as messages_5m,
    (select count(*) from ws_delivery x join ws_message m using (message_id)
      where m.tenant_id=d.tenant_id and m.application_id=d.application_id
        and x.status in ('pending','dispatched')) as pending_deliveries,
    (select coalesce(sum(x.attempts-1) filter (where x.attempts>1),0) from ws_delivery x
      join ws_message m using (message_id) where m.tenant_id=d.tenant_id
        and m.application_id=d.application_id) as retries_total,
    (select round(avg(extract(epoch from x.delivered_at-m.created_at))*1000)::bigint
      from ws_delivery x join ws_message m using (message_id)
      where m.tenant_id=d.tenant_id and m.application_id=d.application_id
        and x.delivered_at > clock_timestamp()-interval '1 hour') as delivery_latency_ms_1h
from dimensions d;

create or replace view ws_alerts as
select 'no_active_server' alert, 'No WebSocket server heartbeat in 60 seconds' detail
where exists (select 1 from ws_server_instance)
  and not exists (select 1 from ws_server_instance where heartbeat_at > clock_timestamp()-interval '60 seconds')
union all
select 'old_pending_delivery', concat('Oldest delivery is ',extract(epoch from clock_timestamp()-min(available_at))::bigint,' seconds old')
from ws_delivery where status in ('pending','dispatched')
having min(available_at) < clock_timestamp()-interval '60 seconds'
union all
select 'repeated_delivery_failure', concat(count(*),' deliveries have at least 3 attempts')
from ws_delivery where status in ('pending','dispatched') and attempts >= 3
having count(*) > 0;

create or replace function ws_cleanup(
    message_retention interval default interval '30 days',
    connection_retention interval default interval '7 days',
    server_retention interval default interval '7 days')
returns jsonb language plpgsql as $$
declare
    removed_messages bigint;
    removed_connections bigint;
    removed_servers bigint;
begin
    update ws_delivery d set status='expired'
      from ws_message m where m.message_id=d.message_id and d.status in ('pending','dispatched')
        and m.expires_at <= clock_timestamp();

    delete from ws_message where created_at < clock_timestamp()-message_retention;
    get diagnostics removed_messages = row_count;

    update ws_delivery d set connection_id=null,server_id=null
      from ws_connection c where c.connection_id=d.connection_id and c.closed_at is not null
        and c.closed_at < clock_timestamp()-connection_retention;
    delete from ws_connection where closed_at is not null
      and closed_at < clock_timestamp()-connection_retention;
    get diagnostics removed_connections = row_count;

    update ws_delivery d set server_id=null where d.server_id in (
      select server_id from ws_server_instance
      where heartbeat_at < clock_timestamp()-server_retention);
    delete from ws_server_instance where heartbeat_at < clock_timestamp()-server_retention
      and not exists (select 1 from ws_connection c where c.server_id=ws_server_instance.server_id);
    get diagnostics removed_servers = row_count;

    return jsonb_build_object('messages',removed_messages,'connections',removed_connections,'servers',removed_servers);
end;
$$;
