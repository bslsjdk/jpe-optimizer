package dev.jpe.optimizer.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jpe.optimizer.runtime.DensityGraphIr;
import dev.jpe.optimizer.runtime.NativeFinalDensityPlan;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Standalone density-graph service.
 *
 * <p>Runs on a plain JDK with no Minecraft and no build tool: it loads the native
 * runtime through FFM and exposes compile, evaluate, parity, and benchmark
 * endpoints plus a small dashboard. This is the harness for the parts of the
 * pipeline that do not need the game.
 */
public final class DensityGraphServer {
    private static final int MAX_ITERATIONS = 500;

    private final Path library;
    private final Map<String, Scenarios.Scenario> scenarios = new LinkedHashMap<>();
    private final Map<String, Compiled> compiled = new ConcurrentHashMap<>();

    private record Compiled(Scenarios.Scenario scenario, NativeFinalDensityPlan plan, Object lock) {}

    private DensityGraphServer(Path library) {
        this.library = library;
        for (Scenarios.Scenario scenario : Scenarios.all()) {
            scenarios.put(scenario.id(), scenario);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8787;
        String bind = "127.0.0.1";
        Path library = NativeFinalDensityPlan.defaultLibrary();
        for (int i = 0; i < args.length; ++i) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind" -> bind = args[++i];
                case "--library" -> library = Path.of(args[++i]);
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    System.err.println("Usage: DensityGraphServer [--port N] [--bind ADDR] [--library PATH]");
                    System.exit(2);
                }
            }
        }
        if (library == null || !library.toFile().isFile()) {
            System.err.println("Native runtime library not found. Build it with:");
            System.err.println("  cargo build --release --manifest-path native/rust-runtime/Cargo.toml");
            System.err.println("or pass --library <path> / -Djpe.nativeLibraryPath=<path>.");
            System.exit(1);
        }

        DensityGraphServer server = new DensityGraphServer(library);
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())));
        http.createContext("/", server::route);
        http.start();
        System.out.printf(Locale.ROOT,
                "JPE density server listening on http://%s:%d  (library=%s)%n", bind, port, library);
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", Dashboard.HTML);
                return;
            }
            if (!path.startsWith("/api/")) {
                sendJson(exchange, 404, Map.of("error", "not found"));
                return;
            }
            switch (path) {
                case "/api/health" -> {
                    requireMethod(method, "GET");
                    sendJson(exchange, 200, health());
                }
                case "/api/scenarios" -> {
                    requireMethod(method, "GET");
                    List<Object> list = new ArrayList<>();
                    for (Scenarios.Scenario scenario : scenarios.values()) {
                        list.add(Scenarios.describe(scenario));
                    }
                    sendJson(exchange, 200, Map.of("scenarios", list));
                }
                case "/api/compile" -> {
                    requireMethod(method, "POST");
                    Map<String, Object> body = readObject(exchange);
                    Compiled entry = compiled(scenarioId(body));
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("id", entry.scenario().id());
                    response.put("nodes", entry.scenario().graph().nodes().size());
                    response.put("root", entry.scenario().graph().rootNode());
                    response.put("handle", entry.plan().handle());
                    response.put("fingerprint", entry.plan().configurationFingerprint());
                    response.put("geometry", Scenarios.geometryJson(entry.scenario().geometry()));
                    sendJson(exchange, 200, response);
                }
                case "/api/evaluate" -> {
                    requireMethod(method, "POST");
                    sendJson(exchange, 200, evaluate(readObject(exchange)));
                }
                case "/api/parity" -> {
                    requireMethod(method, "POST");
                    sendJson(exchange, 200, parity(readObject(exchange)));
                }
                case "/api/bench" -> {
                    requireMethod(method, "POST");
                    sendJson(exchange, 200, bench(readObject(exchange)));
                }
                case "/api/selfcheck" -> {
                    requireMethod(method, "GET");
                    sendJson(exchange, 200, selfcheck());
                }
                default -> sendJson(exchange, 404, Map.of("error", "unknown endpoint " + path));
            }
        } catch (IllegalArgumentException bad) {
            sendJson(exchange, 400, Map.of("error", String.valueOf(bad.getMessage())));
        } catch (Exception failure) {
            failure.printStackTrace();
            sendJson(exchange, 500, Map.of("error", failure.getClass().getSimpleName()
                    + ": " + failure.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private Compiled compiled(String id) {
        Scenarios.Scenario scenario = scenarios.get(id);
        if (scenario == null) throw new IllegalArgumentException("Unknown scenario: " + id);
        return compiled.computeIfAbsent(id, key -> new Compiled(scenario,
                NativeFinalDensityPlan.compile(library, scenario.graph()), new Object()));
    }

    private Map<String, Object> evaluate(Map<String, Object> body) {
        Compiled entry = compiled(scenarioId(body));
        NativeFinalDensityPlan.Geometry geometry = geometry(body, entry.scenario().geometry());
        long started = System.nanoTime();
        float[] values;
        synchronized (entry.lock()) {
            values = entry.plan().evaluate(geometry);
        }
        long elapsed = System.nanoTime() - started;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entry.scenario().id());
        response.put("geometry", Scenarios.geometryJson(geometry));
        response.put("samples", values.length);
        response.put("nanos", elapsed);
        response.put("nsPerSample", (double) elapsed / values.length);
        response.put("stats", stats(values));
        response.put("values", values);
        return response;
    }

    private Map<String, Object> parity(Map<String, Object> body) {
        Compiled entry = compiled(scenarioId(body));
        NativeFinalDensityPlan.Geometry geometry = geometry(body, entry.scenario().geometry());
        float[] nativeValues;
        synchronized (entry.lock()) {
            nativeValues = entry.plan().evaluate(geometry);
        }
        float[] javaValues;
        try {
            javaValues = JavaDensityReference.evaluate(entry.scenario().graph(), geometry);
        } catch (UnsupportedOperationException unsupported) {
            throw new IllegalArgumentException("Reference evaluator: " + unsupported.getMessage());
        }
        NativeFinalDensityPlan.Parity parity = entry.plan().compare(javaValues, nativeValues);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entry.scenario().id());
        response.put("samples", parity.compared());
        response.put("mismatches", parity.mismatches());
        response.put("exact", parity.exact());
        response.put("firstMismatch", parity.firstMismatch());
        response.put("javaValue", parity.javaValue());
        response.put("nativeValue", parity.nativeValue());
        return response;
    }

    private Map<String, Object> bench(Map<String, Object> body) {
        Compiled entry = compiled(scenarioId(body));
        NativeFinalDensityPlan.Geometry geometry = geometry(body, entry.scenario().geometry());
        int iterations = intValue(body, "iterations", 50);
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be in [1, " + MAX_ITERATIONS + "]");
        }
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; ++i) {
            long started = System.nanoTime();
            synchronized (entry.lock()) {
                entry.plan().evaluate(geometry);
            }
            samples[i] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", entry.scenario().id());
        response.put("iterations", iterations);
        response.put("samples", geometry.sampleCount());
        response.put("p50Nanos", samples[iterations / 2]);
        response.put("p95Nanos", samples[Math.min(iterations - 1, (int) Math.ceil(iterations * 0.95) - 1)]);
        response.put("minNanos", samples[0]);
        response.put("maxNanos", samples[iterations - 1]);
        response.put("p50NsPerSample", (double) samples[iterations / 2] / geometry.sampleCount());
        return response;
    }

    /** Compiles every built-in scenario and checks native against the Java reference. */
    private Map<String, Object> selfcheck() {
        List<Object> results = new ArrayList<>();
        boolean allExact = true;
        for (Scenarios.Scenario scenario : scenarios.values()) {
            Compiled entry = compiled(scenario.id());
            NativeFinalDensityPlan.Geometry geometry = scenario.geometry();
            float[] nativeValues;
            synchronized (entry.lock()) {
                nativeValues = entry.plan().evaluate(geometry);
            }
            float[] javaValues = JavaDensityReference.evaluate(scenario.graph(), geometry);
            NativeFinalDensityPlan.Parity parity = entry.plan().compare(javaValues, nativeValues);
            allExact &= parity.exact();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.id());
            row.put("samples", parity.compared());
            row.put("mismatches", parity.mismatches());
            row.put("exact", parity.exact());
            row.put("min", stats(nativeValues).get("min"));
            row.put("max", stats(nativeValues).get("max"));
            results.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exact", allExact);
        response.put("results", results);
        return response;
    }

    private Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("library", library.toString());
        response.put("irVersion", DensityGraphIr.VERSION);
        response.put("scenarios", scenarios.size());
        response.put("compiled", compiled.size());
        response.put("javaVersion", System.getProperty("java.version"));
        response.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        return response;
    }

    private static Map<String, Object> stats(float[] values) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        double sum = 0.0;
        int nan = 0;
        for (float value : values) {
            if (Float.isNaN(value)) { nan++; continue; }
            if (value < min) min = value;
            if (value > max) max = value;
            sum += value;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("min", Float.isFinite(min) ? min : 0.0f);
        stats.put("max", Float.isFinite(max) ? max : 0.0f);
        stats.put("mean", values.length == 0 ? 0.0 : sum / Math.max(1, values.length - nan));
        stats.put("nan", nan);
        return stats;
    }

    private static String scenarioId(Map<String, Object> body) {
        Object id = body.get("scenario");
        if (!(id instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("scenario is required");
        }
        return text;
    }

    private static NativeFinalDensityPlan.Geometry geometry(
            Map<String, Object> body, NativeFinalDensityPlan.Geometry fallback) {
        List<Integer> size = intList(body.get("size"), fallback.sizeX(), fallback.sizeY(), fallback.sizeZ());
        List<Integer> min = intList(body.get("min"), fallback.minX(), fallback.minY(), fallback.minZ());
        List<Integer> step = intList(body.get("step"), fallback.stepX(), fallback.stepY(), fallback.stepZ());
        return new NativeFinalDensityPlan.Geometry(
                size.get(0), size.get(1), size.get(2),
                min.get(0), min.get(1), min.get(2),
                step.get(0), step.get(1), step.get(2));
    }

    private static List<Integer> intList(Object value, int a, int b, int c) {
        if (value == null) return List.of(a, b, c);
        if (!(value instanceof List<?> list) || list.size() != 3) {
            throw new IllegalArgumentException("Expected a three-element array");
        }
        List<Integer> result = new ArrayList<>(3);
        for (Object element : list) {
            if (!(element instanceof Number number)) throw new IllegalArgumentException("Expected numbers");
            result.add(number.intValue());
        }
        return result;
    }

    private static int intValue(Map<String, Object> body, String key, int fallback) {
        Object value = body.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static void requireMethod(String actual, String expected) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalArgumentException("Method " + actual + " is not allowed here");
        }
    }

    private static Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return Map.of();
            return Json.parseObject(text);
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.write(body));
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
