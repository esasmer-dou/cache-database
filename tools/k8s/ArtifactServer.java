import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serves only a curated artifact directory to disposable in-namespace validation pods. */
public final class ArtifactServer {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 16);
        server.setExecutor(new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/", exchange -> {
            try (exchange) {
                String name = exchange.getRequestURI().getPath().substring(1);
                if (!"GET".equals(exchange.getRequestMethod()) || !name.matches("[a-zA-Z0-9._-]{1,160}")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                Path file = root.resolve(name);
                if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, Files.size(file));
                Files.copy(file, exchange.getResponseBody());
            }
        });
        server.start();
        System.out.println("Curated artifact server ready on port 8080");
    }
}
