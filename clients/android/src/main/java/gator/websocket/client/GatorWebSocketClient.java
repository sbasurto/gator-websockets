package gator.websocket.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.GeneralSecurityException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class GatorWebSocketClient extends WebSocketListener {
    private static final Gson GSON = new Gson();

    private final OkHttpClient httpClient;
    private final String url;
    private final Listener listener;
    private WebSocket socket;
    private GatorProtocol.Session session;
    private String usuario;
    private String passphrase;
    private boolean authenticated;

    public GatorWebSocketClient(String url, Listener listener) {
        this(new OkHttpClient(), url, listener);
    }

    public GatorWebSocketClient(OkHttpClient httpClient, String url, Listener listener) {
        this.httpClient = httpClient;
        this.url = url;
        this.listener = listener;
    }

    public synchronized void connect(String usuario, String passphrase) {
        if (socket != null) throw new IllegalStateException("Client is already connected");
        this.usuario = usuario;
        this.passphrase = passphrase;
        socket = httpClient.newWebSocket(new Request.Builder().url(url).build(), this);
    }

    public synchronized boolean send(Object message) {
        if (!authenticated || session == null) throw new IllegalStateException("Client is not authenticated");
        try {
            boolean sent = socket.send(session.seal(GSON.toJson(message)));
            if (!sent) socket.close(1002, "Cannot send encrypted message");
            return sent;
        } catch (GeneralSecurityException error) {
            fail(error);
            return false;
        }
    }

    public synchronized void close() {
        if (socket != null) socket.close(1000, "");
    }

    public synchronized boolean isAuthenticated() {
        return authenticated;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        listener.onState("connected");
    }

    @Override
    public synchronized void onMessage(WebSocket webSocket, String text) {
        try {
            JsonObject message = GSON.fromJson(session == null ? text : session.open(text), JsonObject.class);
            if (session == null) {
                if (!"askauth".equals(string(message, "type"))) throw new GeneralSecurityException("Expected HPKE key offer");
                JsonObject data = message.getAsJsonObject("data");
                if (data == null || !"1".equals(string(data, "version"))
                        || !GatorProtocol.SUITE.equals(string(data, "suite"))) {
                    throw new GeneralSecurityException("Unsupported Gator WebSocket encryption suite");
                }
                JsonObject authentication = new JsonObject();
                authentication.addProperty("type", "authenticateme");
                authentication.addProperty("message", passphrase);
                JsonObject authenticationData = new JsonObject();
                authenticationData.addProperty("usuario", usuario);
                authentication.add("data", authenticationData);
                session = GatorProtocol.start(string(data, "keyId"), string(message, "keyForAuth"),
                        GSON.toJson(authentication));
                if (!webSocket.send(session.initialEnvelope)) {
                    throw new GeneralSecurityException("Cannot send encrypted authentication");
                }
                usuario = null;
                passphrase = null;
                return;
            }
            String type = string(message, "type");
            if ("authsuccess".equals(type)) {
                authenticated = true;
                listener.onState("authenticated");
            } else if ("forcedclosure".equals(type)) {
                listener.onError(new GeneralSecurityException(string(message, "estatusDesc")));
                webSocket.close(1002, "Connection rejected");
            } else if ("event".equals(type)) {
                listener.onEvent(message);
            } else {
                listener.onMessage(message);
            }
        } catch (Exception error) {
            fail(error);
        }
    }

    @Override
    public synchronized void onClosed(WebSocket webSocket, int code, String reason) {
        authenticated = false;
        session = null;
        socket = null;
        usuario = null;
        passphrase = null;
        listener.onState("closed");
    }

    @Override
    public synchronized void onFailure(WebSocket webSocket, Throwable error, Response response) {
        authenticated = false;
        session = null;
        socket = null;
        usuario = null;
        passphrase = null;
        listener.onError(error);
        listener.onState("closed");
    }

    private void fail(Throwable error) {
        listener.onError(error);
        if (socket != null) socket.close(1002, "Invalid encrypted message");
    }

    private static String string(JsonObject object, String name) throws GeneralSecurityException {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            throw new GeneralSecurityException("Missing " + name);
        }
        return object.get(name).getAsString();
    }

    public interface Listener {
        default void onState(String state) {}
        default void onMessage(JsonObject message) {}
        default void onEvent(JsonObject event) {}
        default void onError(Throwable error) {}
    }
}
