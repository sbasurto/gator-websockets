package gator.websockets.realtime;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gator.lib.db.conf.GappDBConfFile;
import gator.lib.io.files.GappFiles;
import gator.lib.uihelpers.GappUIHelper;
import gator.websockets.helpers.GatorWSProperties;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.postgresql.PGConnection;

/** PostgreSQL-backed routing, delivery, subscription and presence for protocol v2. */
public final class GatorRealtimeCoordinator implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final Set<String> TARGET_KINDS = Set.of("connection", "user", "topic", "tenant");
    private final String url;
    private final Properties dbProperties = new Properties();
    private final UUID serverId = UUID.randomUUID();
    private final String hostname;
    private final String tenantId;
    private final String applicationId;
    private final int heartbeatSeconds;
    private final int leaseSeconds;
    private final Predicate<Delivery> deliveryHandler;
    private final FcmPushSender pushSender;
    private final AtomicBoolean running = new AtomicBoolean();

    public GatorRealtimeCoordinator(GatorWSProperties properties, Predicate<Delivery> deliveryHandler) {
        GappDBConfFile database = database(properties.getRealtimeDbConfigurationFile());
        url = "jdbc:postgresql://" + database.getServer() + ":" + database.getPortNumber() + "/" + database.getSID();
        dbProperties.setProperty("user", database.getUser());
        dbProperties.setProperty("password", database.getSecret());
        if("true".equalsIgnoreCase(database.getSSL())) dbProperties.setProperty("ssl", "true");
        dbProperties.setProperty("connectTimeout", "10");
        tenantId = properties.getTenantId();
        applicationId = properties.getApplicationId();
        heartbeatSeconds = properties.getServerHeartbeatSeconds();
        leaseSeconds = properties.getServerLeaseSeconds();
        this.deliveryHandler = deliveryHandler;
        try {
            pushSender = properties.getFcmProjectId().isEmpty() ? null : new FcmPushSender(properties.getFcmProjectId());
        } catch(Exception error) {
            throw new IllegalArgumentException("Cannot initialize FCM", error);
        }
        hostname = hostname();
    }

    public UUID serverId() {
        return serverId;
    }

    public void start() {
        if(!running.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon(true).name("gator-realtime-" + serverId).start(this::run);
        if(pushSender != null) Thread.ofPlatform().daemon(true).name("gator-fcm-" + serverId).start(this::runPush);
    }

    public void connected(UUID connectionId, String userId) throws Exception {
        try(Connection connection = connection()) {
            connection.setAutoCommit(false);
            heartbeat(connection);
            try(PreparedStatement statement = connection.prepareStatement("""
                    insert into ws_connection(connection_id,server_id,tenant_id,application_id,user_id)
                    values (?,?,?,?,?)
                    on conflict (connection_id) do update set server_id=excluded.server_id,
                      tenant_id=excluded.tenant_id, application_id=excluded.application_id,
                      user_id=excluded.user_id, connected_at=clock_timestamp(), last_seen_at=clock_timestamp(),
                      closed_at=null, close_reason=null
                    """)) {
                statement.setObject(1, connectionId);
                statement.setObject(2, serverId);
                statement.setString(3, tenantId);
                statement.setString(4, applicationId);
                statement.setString(5, userId);
                statement.executeUpdate();
            }
            try(PreparedStatement statement = connection.prepareStatement("""
                    update ws_delivery set connection_id=?, server_id=?
                    where delivery_id in (
                      select delivery_id from ws_delivery
                      where connection_id is null and target_user_id=? and status='pending'
                      order by delivery_id for update skip locked
                    )
                    """)) {
                statement.setObject(1, connectionId);
                statement.setObject(2, serverId);
                statement.setString(3, userId);
                statement.executeUpdate();
            }
            notifyDeliveries(connection);
            connection.commit();
        }
    }

    public void disconnected(UUID connectionId, String reason) {
        try(Connection connection = connection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement statement = connection.prepareStatement("""
                    update ws_connection set closed_at=clock_timestamp(), close_reason=?
                    where connection_id=? and closed_at is null
                    """)) {
                statement.setString(1, reason);
                statement.setObject(2, connectionId);
                statement.executeUpdate();
            }
            requeueUserDeliveries(connection, "d.connection_id=?", connectionId);
            notifyDeliveries(connection);
            connection.commit();
        } catch(Exception error) {
            System.err.println("Cannot close realtime connection: " + error.getMessage());
        }
    }

    public String handle(String encoded, Principal principal) {
        try {
            JsonObject request = JsonParser.parseString(encoded).getAsJsonObject();
            if(request.get("v").getAsInt() != 2) throw new IllegalArgumentException("Unsupported protocol version");
            return switch(requiredString(request, "op")) {
                case "publish" -> publish(request, principal).toString();
                case "subscribe" -> subscriptions(request, principal, true).toString();
                case "unsubscribe" -> subscriptions(request, principal, false).toString();
                case "ack" -> ack(request, principal).toString();
                case "presence" -> presence(principal).toString();
                default -> error("unsupported_operation").toString();
            };
        } catch(IllegalArgumentException error) {
            return error(error.getMessage()).toString();
        } catch(Exception error) {
            System.err.println("Realtime request failed: " + error.getMessage());
            return error("request_failed").toString();
        }
    }

    public static boolean isV2(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            return json.has("v") && json.get("v").getAsInt() == 2 && json.has("op");
        } catch(Exception ignored) {
            return false;
        }
    }

    private JsonObject publish(JsonObject request, Principal principal) throws Exception {
        if(!principal.scopes().contains("messages:send")) throw new IllegalArgumentException("not_authorized");
        UUID clientMessageId = UUID.fromString(requiredString(request, "clientMessageId"));
        JsonObject target = request.getAsJsonObject("target");
        if(target == null) throw new IllegalArgumentException("target is required");
        String kind = requiredString(target, "kind");
        if(!TARGET_KINDS.contains(kind)) throw new IllegalArgumentException("Invalid target kind");
        if("tenant".equals(kind) && !principal.scopes().contains("broadcast:tenant")) {
            throw new IllegalArgumentException("not_authorized");
        }
        List<String> ids = strings(target.getAsJsonArray("ids"));
        if(!"tenant".equals(kind) && (ids.isEmpty() || ids.size() > 100)) {
            throw new IllegalArgumentException("target ids must contain 1 to 100 values");
        }
        if("connection".equals(kind)) ids.forEach(UUID::fromString);
        JsonObject payload = request.getAsJsonObject("payload");
        if(payload == null || !payload.has("type")) throw new IllegalArgumentException("payload.type is required");

        UUID messageId = UUID.randomUUID();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 2);
        envelope.addProperty("op", "message");
        envelope.addProperty("messageId", messageId.toString());
        envelope.addProperty("clientMessageId", clientMessageId.toString());
        envelope.addProperty("tenantId", principal.tenantId());
        envelope.addProperty("applicationId", principal.applicationId());
        JsonObject sender = new JsonObject();
        sender.addProperty("userId", principal.userId());
        envelope.add("sender", sender);
        envelope.add("target", target.deepCopy());
        envelope.add("payload", payload.deepCopy());
        envelope.addProperty("createdAt", Instant.now().toString());

        try(Connection connection = connection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement statement = connection.prepareStatement("""
                    insert into ws_message(message_id,client_message_id,tenant_id,application_id,
                      sender_user_id,target_kind,envelope)
                    values (?,?,?,?,?,?,?::jsonb)
                    on conflict (tenant_id,application_id,sender_user_id,client_message_id) do nothing
                    """)) {
                statement.setObject(1, messageId);
                statement.setObject(2, clientMessageId);
                statement.setString(3, principal.tenantId());
                statement.setString(4, principal.applicationId());
                statement.setString(5, principal.userId());
                statement.setString(6, kind);
                statement.setString(7, envelope.toString());
                if(statement.executeUpdate() == 0) {
                    connection.rollback();
                    return existingAccepted(clientMessageId, principal);
                }
            }
            List<TargetConnection> targets = targets(connection, kind, ids, principal);
            try(PreparedStatement statement = connection.prepareStatement("""
                    insert into ws_delivery(message_id,target_user_id,connection_id,server_id)
                    values (?,?,?,?) on conflict do nothing
                    """)) {
                for(TargetConnection targetConnection: targets) {
                    statement.setObject(1, messageId);
                    statement.setString(2, targetConnection.userId());
                    statement.setObject(3, targetConnection.connectionId());
                    statement.setObject(4, targetConnection.serverId());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            if(targets.isEmpty() && "user".equals(kind)) {
                try(PreparedStatement statement = connection.prepareStatement("""
                        insert into ws_delivery(message_id,target_user_id) values (?,?)
                        """)) {
                    for(String userId: ids) {
                        statement.setObject(1, messageId);
                        statement.setString(2, userId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
            notifyDeliveries(connection);
            connection.commit();
        }
        return accepted(messageId, clientMessageId);
    }

    private JsonObject subscriptions(JsonObject request, Principal principal, boolean subscribe) throws Exception {
        List<String> topics = strings(request.getAsJsonArray("topics"));
        if(topics.isEmpty() || topics.size() > 100) throw new IllegalArgumentException("topics must contain 1 to 100 values");
        JsonArray accepted = new JsonArray();
        try(Connection connection = connection()) {
            String sql = subscribe
                    ? "insert into ws_subscription(connection_id,topic) values (?,?) on conflict do nothing"
                    : "delete from ws_subscription where connection_id=? and topic=?";
            try(PreparedStatement statement = connection.prepareStatement(sql)) {
                for(String topic: topics) {
                    String canonical = topic(principal, topic);
                    statement.setObject(1, principal.connectionId());
                    statement.setString(2, canonical);
                    statement.addBatch();
                    accepted.add(topic);
                }
                statement.executeBatch();
            }
        }
        JsonObject response = response(subscribe ? "subscribed" : "unsubscribed");
        response.add("topics", accepted);
        return response;
    }

    private JsonObject ack(JsonObject request, Principal principal) throws Exception {
        UUID messageId = UUID.fromString(requiredString(request, "messageId"));
        if(!"delivered".equals(requiredString(request, "status"))) throw new IllegalArgumentException("Invalid ACK status");
        try(Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                update ws_delivery set status='delivered', delivered_at=clock_timestamp()
                where message_id=? and connection_id=? and status in ('pending','dispatched')
                """)) {
            statement.setObject(1, messageId);
            statement.setObject(2, principal.connectionId());
            statement.executeUpdate();
        }
        JsonObject response = response("acked");
        response.addProperty("messageId", messageId.toString());
        return response;
    }

    private JsonObject presence(Principal principal) throws Exception {
        JsonArray users = new JsonArray();
        try(Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                select c.user_id,count(*) connections from ws_connection c
                join ws_server_instance s on s.server_id=c.server_id
                where c.tenant_id=? and c.application_id=? and c.closed_at is null
                  and s.heartbeat_at > clock_timestamp()-(? * interval '1 second')
                group by c.user_id order by c.user_id
                """)) {
            statement.setString(1, principal.tenantId());
            statement.setString(2, principal.applicationId());
            statement.setInt(3, leaseSeconds);
            try(ResultSet result = statement.executeQuery()) {
                while(result.next()) {
                    JsonObject user = new JsonObject();
                    user.addProperty("userId", result.getString(1));
                    user.addProperty("connections", result.getInt(2));
                    users.add(user);
                }
            }
        }
        JsonObject response = response("presence");
        response.add("users", users);
        return response;
    }

    private List<TargetConnection> targets(Connection connection, String kind, List<String> ids, Principal principal) throws Exception {
        String filter = switch(kind) {
            case "connection" -> "c.connection_id = any (?::uuid[])";
            case "user" -> "c.user_id = any (?::text[])";
            case "topic" -> "exists (select 1 from ws_subscription x where x.connection_id=c.connection_id and x.topic = any (?::text[]))";
            case "tenant" -> "true";
            default -> throw new IllegalArgumentException("Invalid target kind");
        };
        String sql = """
                select c.connection_id,c.server_id,c.user_id from ws_connection c
                join ws_server_instance s on s.server_id=c.server_id
                where c.tenant_id=? and c.application_id=? and c.closed_at is null
                  and s.heartbeat_at > clock_timestamp()-(? * interval '1 second') and
                """ + filter;
        List<TargetConnection> targets = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, principal.tenantId());
            statement.setString(2, principal.applicationId());
            statement.setInt(3, leaseSeconds);
            if(!"tenant".equals(kind)) {
                Object[] values = "topic".equals(kind)
                        ? ids.stream().map(value -> topic(principal, value)).toArray()
                        : ids.toArray();
                statement.setArray(4, connection.createArrayOf("connection".equals(kind) ? "uuid" : "text", values));
            }
            try(ResultSet result = statement.executeQuery()) {
                while(result.next()) targets.add(new TargetConnection(
                        result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getString(3)));
            }
        }
        return targets;
    }

    private JsonObject existingAccepted(UUID clientMessageId, Principal principal) throws Exception {
        try(Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                select message_id from ws_message where tenant_id=? and application_id=?
                  and sender_user_id=? and client_message_id=?
                """)) {
            statement.setString(1, principal.tenantId());
            statement.setString(2, principal.applicationId());
            statement.setString(3, principal.userId());
            statement.setObject(4, clientMessageId);
            try(ResultSet result = statement.executeQuery()) {
                if(result.next()) return accepted(result.getObject(1, UUID.class), clientMessageId);
            }
        }
        throw new IllegalStateException("Cannot recover idempotent message");
    }

    private void run() {
        while(running.get()) {
            try(Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.execute("listen gator_ws_delivery");
                PGConnection postgres = connection.unwrap(PGConnection.class);
                long nextHeartbeat = 0;
                while(running.get() && !connection.isClosed()) {
                    long now = System.nanoTime();
                    if(now >= nextHeartbeat) {
                        heartbeat(connection);
                        nextHeartbeat = now + heartbeatSeconds * 1_000_000_000L;
                    }
                    drain(connection);
                    postgres.getNotifications(1000);
                }
            } catch(Exception error) {
                System.err.println("Realtime coordinator retrying: " + error.getMessage());
                try { Thread.sleep(1000); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void runPush() {
        while(running.get()) {
            try(Connection connection = connection()) {
                for(PendingPush push: claimPushes(connection)) {
                    FcmPushSender.Result result = pushSender.send(push.token(), push.payload());
                    finishPush(connection, push, result);
                }
            } catch(Exception error) {
                System.err.println("FCM dispatcher retrying: " + error.getMessage());
            }
            try { Thread.sleep(1000); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private List<PendingPush> claimPushes(Connection connection) throws Exception {
        List<PendingPush> pushes = new ArrayList<>();
        try(Statement statement = connection.createStatement()) {
            statement.executeUpdate("update ws_push_delivery set status='expired' where status='pending' and expires_at<=clock_timestamp()");
        }
        try(PreparedStatement statement = connection.prepareStatement("""
                with claimed as (
                  select push_delivery_id from ws_push_delivery
                  where status='pending' and available_at<=clock_timestamp()
                    and attempts<8 and expires_at>clock_timestamp()
                  order by push_delivery_id for update skip locked limit 20
                )
                update ws_push_delivery p set attempts=p.attempts+1,
                  available_at=clock_timestamp()+interval '60 seconds'
                from claimed where p.push_delivery_id=claimed.push_delivery_id
                returning p.push_delivery_id,p.target_token,p.payload::text,p.attempts
                """)) {
            try(ResultSet result = statement.executeQuery()) {
                while(result.next()) pushes.add(new PendingPush(
                        result.getLong(1), result.getString(2), result.getString(3), result.getInt(4)));
            }
        }
        return pushes;
    }

    private void finishPush(Connection connection, PendingPush push, FcmPushSender.Result result) throws Exception {
        String status = result.sent() ? "delivered" : result.permanent() || push.attempts() >= 8 ? "failed" : "pending";
        try(PreparedStatement statement = connection.prepareStatement("""
                update ws_push_delivery set status=?,delivered_at=case when ?='delivered' then clock_timestamp() end,
                  last_error=?,available_at=clock_timestamp()+(? * interval '1 second')
                where push_delivery_id=?
                """)) {
            statement.setString(1, status);
            statement.setString(2, status);
            statement.setString(3, result.error());
            statement.setInt(4, FcmPushSender.retrySeconds(push.attempts()));
            statement.setLong(5, push.id());
            statement.executeUpdate();
        }
    }

    private void heartbeat(Connection connection) throws Exception {
        try(PreparedStatement statement = connection.prepareStatement("""
                insert into ws_server_instance(server_id,hostname) values (?,?)
                on conflict (server_id) do update set heartbeat_at=clock_timestamp(),hostname=excluded.hostname
                """)) {
            statement.setObject(1, serverId);
            statement.setString(2, hostname);
            statement.executeUpdate();
        }
        requeueUserDeliveries(connection, """
                d.connection_id in (select c.connection_id from ws_connection c join ws_server_instance s
                  on s.server_id=c.server_id where c.closed_at is null
                  and s.heartbeat_at <= clock_timestamp()-(? * interval '1 second'))
                """, leaseSeconds);
        try(PreparedStatement statement = connection.prepareStatement("""
                update ws_connection c set closed_at=clock_timestamp(),close_reason='server_lease_expired'
                from ws_server_instance s where c.server_id=s.server_id and c.closed_at is null
                  and s.heartbeat_at <= clock_timestamp()-(? * interval '1 second')
                """)) {
            statement.setInt(1, leaseSeconds);
            statement.executeUpdate();
        }
    }

    private static void requeueUserDeliveries(Connection connection, String filter, Object value) throws Exception {
        try(PreparedStatement statement = connection.prepareStatement("""
                update ws_delivery d set connection_id=null,server_id=null,status='pending',
                  available_at=clock_timestamp(),dispatched_at=null
                from ws_message m where m.message_id=d.message_id and m.target_kind='user'
                  and d.status in ('pending','dispatched') and
                """ + filter)) {
            statement.setObject(1, value);
            statement.executeUpdate();
        }
    }

    private void drain(Connection connection) throws Exception {
        List<PendingDelivery> deliveries = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("""
                select d.delivery_id,d.connection_id,d.message_id,m.envelope::text
                from ws_delivery d join ws_message m on m.message_id=d.message_id
                where d.server_id=? and d.status in ('pending','dispatched')
                  and d.available_at<=clock_timestamp()
                  and (m.expires_at is null or m.expires_at>clock_timestamp())
                order by d.delivery_id limit 100
                """)) {
            statement.setObject(1, serverId);
            try(ResultSet result = statement.executeQuery()) {
                while(result.next()) deliveries.add(new PendingDelivery(result.getLong(1),
                        result.getObject(2, UUID.class), result.getObject(3, UUID.class), result.getString(4)));
            }
        }
        for(PendingDelivery pending: deliveries) {
            boolean sent = deliveryHandler.test(new Delivery(pending.connectionId(), pending.messageId(), pending.envelope()));
            try(PreparedStatement statement = connection.prepareStatement(sent ? """
                    update ws_delivery set status='dispatched',attempts=attempts+1,
                      dispatched_at=clock_timestamp(),available_at=clock_timestamp()+interval '15 seconds'
                    where delivery_id=? and status in ('pending','dispatched')
                    """ : """
                    update ws_delivery set attempts=attempts+1,available_at=clock_timestamp()+interval '5 seconds'
                    where delivery_id=? and status in ('pending','dispatched')
                    """)) {
                statement.setLong(1, pending.deliveryId());
                statement.executeUpdate();
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(url, dbProperties);
    }

    private static GappDBConfFile database(String indexFile) {
        try {
            String dbFile = new GappUIHelper().getIndexConfig2(indexFile, "system").getConfigurationFile();
            return GSON.fromJson(Files.readString(Path.of(GappFiles.CONF_DIR, dbFile)), GappDBConfFile.class);
        } catch(Exception error) {
            throw new IllegalArgumentException("Cannot load realtime database configuration", error);
        }
    }

    private static void notifyDeliveries(Connection connection) throws Exception {
        try(Statement statement = connection.createStatement()) {
            statement.execute("select pg_notify('gator_ws_delivery','delivery')");
        }
    }

    private static String requiredString(JsonObject object, String name) {
        if(object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String value = object.get(name).getAsString().trim();
        if(value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static List<String> strings(JsonArray array) {
        if(array == null) return List.of();
        List<String> values = new ArrayList<>();
        array.forEach(value -> {
            String string = value.getAsString().trim();
            if(!string.isEmpty() && !values.contains(string)) values.add(string);
        });
        return values;
    }

    private static String topic(Principal principal, String value) {
        if(!value.matches("[A-Za-z0-9._/-]{1,128}") || value.contains("..") || value.startsWith("/")) {
            throw new IllegalArgumentException("Invalid topic");
        }
        return "tenant/" + principal.tenantId() + "/app/" + principal.applicationId() + "/" + value;
    }

    private static JsonObject accepted(UUID messageId, UUID clientMessageId) {
        JsonObject response = response("accepted");
        response.addProperty("messageId", messageId.toString());
        response.addProperty("clientMessageId", clientMessageId.toString());
        return response;
    }

    private static JsonObject response(String operation) {
        JsonObject response = new JsonObject();
        response.addProperty("v", 2);
        response.addProperty("op", operation);
        return response;
    }

    private static JsonObject error(String reason) {
        JsonObject response = response("error");
        response.addProperty("reason", reason == null || reason.isBlank() ? "request_failed" : reason);
        return response;
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch(Exception ignored) { return "unknown"; }
    }

    @Override
    public void close() {
        running.set(false);
    }

    public record Principal(String tenantId, String applicationId, String userId, UUID connectionId, Set<String> scopes) {
        public Principal {
            scopes = Set.copyOf(scopes == null ? new HashSet<>() : scopes);
        }
    }
    public record Delivery(UUID connectionId, UUID messageId, String envelope) {}
    private record TargetConnection(UUID connectionId, UUID serverId, String userId) {}
    private record PendingDelivery(long deliveryId, UUID connectionId, UUID messageId, String envelope) {}
    private record PendingPush(long id, String token, String payload, int attempts) {}
}
