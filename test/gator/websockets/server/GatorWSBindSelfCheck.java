package gator.websockets.server;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public final class GatorWSBindSelfCheck {
        private GatorWSBindSelfCheck() {}

        public static void run() throws Exception {
                Path root = Files.createTempDirectory("gator-ws-bind-");
                try {
                        Path conf = Files.createDirectory(root.resolve("conf"));
                        Files.createDirectory(root.resolve("logs"));
                        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(),
                                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keystore", conf.resolve("test.p12").toString(),
                                "-storetype", "PKCS12", "-storepass", "test-only-password", "-dname", "CN=localhost", "-validity", "1")
                                .redirectErrorStream(true).redirectOutput(root.resolve("keytool.log").toFile()).start();
                        assert keytool.waitFor() == 0 : "Cannot create local TLS fixture";
                        checkListener(root, "", false);
                        checkListener(root, "127.0.0.1", false);
                        checkListener(root, "127.0.0.1", true);
                } finally {
                        try(var files = Files.walk(root)) {
                                for(Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                        }
                }
        }

        private static void checkListener(Path root, String address, boolean tls) throws Exception {
                int port;
                try(var available = new ServerSocket(0)) { port = available.getLocalPort(); }
                Files.writeString(root.resolve("conf/websocket.properties"), "port=" + port + "\nwithSSL=" + tls
                        + "\nwithDebug=false\nrealtimeEnabled=false\n"
                        + (address.isEmpty() ? "" : "bindAddress=" + address + "\n")
                        + "truststore=test.p12\nalias=server\npassphrase=test-only-password\n");
                Path log = root.resolve("server.log");
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp", System.getProperty("java.class.path"), "gator.websockets.GatorWSRunner")
                        .redirectErrorStream(true).redirectOutput(log.toFile());
                builder.environment().put("GAPPS_FILES_ROOT_VAR_NAME", "GATOR_WS_TEST_ROOT");
                builder.environment().put("GATOR_WS_TEST_ROOT", root.toString());
                builder.environment().remove("GATOR_WS_PORT");
                Process child = builder.start();
                try {
                        boolean listening = false;
                        for(int attempt = 0; attempt < 100 && child.isAlive(); attempt++) {
                                try(var socket = new Socket()) {
                                        socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                                        listening = true;
                                        break;
                                } catch(java.io.IOException notReady) { Thread.sleep(50); }
                        }
                        assert listening : "Listener did not start: " + Files.readString(log);
                        boolean otherAddressAvailable;
                        try(var other = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.2"))) {
                                otherAddressAvailable = true;
                        } catch(java.net.BindException occupied) { otherAddressAvailable = false; }
                        assert otherAddressAvailable == !address.isEmpty() : "Incorrect bind scope for " + address + "; TLS=" + tls;
                } finally {
                        child.destroy();
                        if(!child.waitFor(5, TimeUnit.SECONDS)) {
                                child.destroyForcibly();
                                child.waitFor();
                        }
                }
        }
}
