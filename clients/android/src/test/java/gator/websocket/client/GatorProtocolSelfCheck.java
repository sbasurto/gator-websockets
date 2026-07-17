package gator.websocket.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;

public final class GatorProtocolSelfCheck {
    private GatorProtocolSelfCheck() {}

    public static void main(String[] args) throws Exception {
        byte[] recipientPublic = hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d");
        X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(
                hex("52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736"), 0);
        GatorProtocol.Setup setup = GatorProtocol.setup(recipientPublic, ephemeral);
        assert Arrays.equals(setup.encapsulation,
                hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431"));
        assert Arrays.equals(setup.sharedSecret,
                hex("fe0e18c9f024ce43799ae393c7e8fe8fce9d218875e8227b0187c04e7d2ea1fc"));

        String authentication = "{\"type\":\"authenticateme\"}";
        GatorProtocol.Session client = GatorProtocol.start("test-key", recipientPublic, authentication, ephemeral);
        JsonObject initial = JsonParser.parseString(client.initialEnvelope).getAsJsonObject();
        byte[] sharedSecret = setup.sharedSecret;
        assert Arrays.equals(sharedSecret, setup.sharedSecret);
        GatorProtocol.Context context = GatorProtocol.context(sharedSecret, GatorProtocol.bytes("gator-websockets-v1"));
        byte[] opened = GatorProtocol.open(context.key, context.baseNonce, 0,
                GatorProtocol.bytes("gator-ws-v1|test-key|hpke|0"),
                GatorProtocol.decode(initial.get("ciphertext").getAsString()));
        assert authentication.equals(new String(opened, StandardCharsets.UTF_8));

        GatorProtocol.CipherState serverInbound = new GatorProtocol.CipherState(
                GatorProtocol.export(context, "gator-ws-v1/client-to-server", 44));
        JsonObject request = JsonParser.parseString(client.seal("{\"type\":\"getuserlist\"}")).getAsJsonObject();
        assert "{\"type\":\"getuserlist\"}".equals(new String(serverInbound.open(0,
                GatorProtocol.bytes("gator-ws-v1|test-key|client-to-server|0"),
                GatorProtocol.decode(request.get("ciphertext").getAsString())), StandardCharsets.UTF_8));

        GatorProtocol.CipherState serverOutbound = new GatorProtocol.CipherState(
                GatorProtocol.export(context, "gator-ws-v1/server-to-client", 44));
        String responseCiphertext = GatorProtocol.encode(serverOutbound.seal(
                GatorProtocol.bytes("gator-ws-v1|test-key|server-to-client|0"),
                GatorProtocol.bytes("{\"type\":\"userslist\"}")));
        String response = "{\"version\":1,\"keyId\":\"test-key\",\"sequence\":0,\"ciphertext\":\"" + responseCiphertext + "\"}";
        assert "{\"type\":\"userslist\"}".equals(client.open(response));
        try {
            client.open(response);
            throw new AssertionError("Replay must be rejected");
        } catch (java.security.GeneralSecurityException expected) {
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }
}
