// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.metrics.MetricRegistry;
import cloud.victus.core.metrics.PrometheusExposition;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Serves the Prometheus exposition on a loopback HTTP endpoint, off the server tick thread. Reads the
 * (thread-safe) {@link MetricRegistry} on the HTTP worker thread; never touches the game state.
 */
final class MetricsHttpEndpoint {
    private final MetricRegistry registry;
    private final String bind;
    private final int port;
    private HttpServer server;

    MetricsHttpEndpoint(MetricRegistry registry, String bind, int port) {
        this.registry = registry;
        this.bind = bind;
        this.port = port;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = PrometheusExposition.write(registry).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", PrometheusExposition.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/", exchange -> {
            byte[] body = ("Victus Engine metrics. See /metrics\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        // small daemon pool so a slow scrape can't block others; loopback only
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "victus-metrics-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    String url() {
        return "http://" + bind + ":" + port + "/metrics";
    }
}
