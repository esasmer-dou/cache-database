import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Bounded, read-only check of the exact external sample Redis endpoint. */
public final class RedisServicePreflight {
    private static final int TIMEOUT_MILLIS = 2000;

    public static void main(String[] args) {
        String phase = "configuration";
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            String namespace = System.getenv("CACHEDB_TEST_NAMESPACE");
            if (namespace == null || !namespace.matches("cachedb-[a-z0-9-]+"))
                throw new IllegalArgumentException();
            URI uri = URI.create(System.getenv("CACHEDB_TEST_REDIS_URI"));
            boolean tls = "rediss".equals(uri.getScheme());
            String suffix = "." + namespace + ".svc.cluster.local";
            if ((!tls && !"redis".equals(uri.getScheme())) || uri.getHost() == null
                    || !uri.getHost().endsWith(suffix) || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getPort() == 0)
                throw new IllegalArgumentException();
            String path = uri.getPath();
            int database = path == null || path.isEmpty() ? 0
                    : Integer.parseInt(path.substring(1));
            if (database < 0 || (path != null && !path.isEmpty() && !path.matches("/[0-9]+")))
                throw new IllegalArgumentException();
            int port = uri.getPort() < 0 ? 6379 : uri.getPort();
            phase = "service-connect";
            try (Socket transport = new Socket()) {
                transport.connect(new InetSocketAddress(uri.getHost(), port), TIMEOUT_MILLIS);
                transport.setSoTimeout(TIMEOUT_MILLIS);
                try (Socket socket = tls ? ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(transport, uri.getHost(), port, true) : transport) {
                    socket.setSoTimeout(TIMEOUT_MILLIS);
                    if (socket instanceof SSLSocket ssl) {
                        var parameters = ssl.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        ssl.setSSLParameters(parameters);
                        ssl.startHandshake();
                    }
                    var input = new BufferedInputStream(socket.getInputStream());
                    var output = new BufferedOutputStream(socket.getOutputStream());
                    String userInfo = uri.getRawUserInfo();
                    if (userInfo != null) {
                        phase = "authenticate";
                        String[] credentials = userInfo.split(":", 2);
                        if (credentials.length != 2) throw new IllegalArgumentException();
                        String user = decode(credentials[0]);
                        String password = decode(credentials[1]);
                        if (password.isEmpty()) throw new IllegalArgumentException();
                        if (user.isEmpty()) command(input, output, "OK", "AUTH", password);
                        else command(input, output, "OK", "AUTH", user, password);
                    }
                    phase = "database-select";
                    command(input, output, "OK", "SELECT", Integer.toString(database));
                    phase = "service-ping";
                    command(input, output, "PONG", "PING");
                }
            }
            System.out.println("Redis service preflight PASS (connect, database, PING; authentication="
                    + (uri.getRawUserInfo() == null ? "not-configured" : "verified") + ")");
        } catch (Exception failure) {
            // Never print the URI, credentials, server response or exception message.
            System.err.println("Redis service preflight FAIL: " + phase + " ("
                    + failure.getClass().getSimpleName() + ")");
            System.exit(1);
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void command(BufferedInputStream input, BufferedOutputStream output,
                                String expected, String... arguments) throws IOException {
        output.write(("*" + arguments.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String argument : arguments) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
        output.flush();
        int kind = input.read();
        var line = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            int next = input.read();
            if (next < 0) throw new IOException();
            if (next == '\r') {
                if (input.read() != '\n' || kind != '+' || !expected.contentEquals(line))
                    throw new IOException();
                return;
            }
            line.append((char) next);
        }
        throw new IOException();
    }
}
