import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redirect latency benchmark. Needs only a JDK 21 (single-file source launch, no build):
 *
 * <pre>
 *   java scripts/RedirectBench.java                              # localhost:8080, 5000 requests, 32 concurrent
 *   java scripts/RedirectBench.java http://host:8080 20000 64
 * </pre>
 *
 * Creates one link, warms up, then sends N redirect requests at the given concurrency and
 * reports latency percentiles and throughput. Used to compare the redirect path before and
 * after a change (Phase 2: before/after click analytics). Numbers are only comparable when
 * taken on the same machine, so always compare runs taken together, never across machines.
 */
public class RedirectBench {

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int requests = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int warmup = Math.max(500, requests / 10);

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String code = createLink(http, base);
        URI target = URI.create(base + "/" + code);
        System.out.printf("Benchmarking %s  (requests=%d, concurrency=%d, warmup=%d)%n",
                target, requests, concurrency, warmup);

        Result w = run(http, target, warmup, concurrency); // JIT, connection pool and DB caches warm
        long start = System.nanoTime();
        Result r = run(http, target, requests, concurrency);
        double seconds = (System.nanoTime() - start) / 1e9;
        // Every successful GET is a click, so this is the expected totalClicks for the link.
        int ok302 = (warmup - w.non302) + (requests - r.non302);

        long[] lat = r.latenciesNanos;
        Arrays.sort(lat);
        System.out.printf("  throughput : %.0f req/s%n", requests / seconds);
        System.out.printf("  p50        : %.2f ms%n", pct(lat, 50));
        System.out.printf("  p95        : %.2f ms%n", pct(lat, 95));
        System.out.printf("  p99        : %.2f ms%n", pct(lat, 99));
        System.out.printf("  max        : %.2f ms%n", lat[lat.length - 1] / 1e6);
        System.out.printf("  non-302    : %d%n", r.non302);
        System.out.printf("  code       : %s  (%d successful redirects incl. warmup)%n", code, ok302);
        if (r.non302 > 0) {
            System.exit(1);
        }
    }

    record Result(long[] latenciesNanos, int non302) {
    }

    static Result run(HttpClient http, URI target, int n, int concurrency) throws Exception {
        long[] lat = new long[n];
        AtomicInteger next = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        HttpRequest req = HttpRequest.newBuilder(target).GET()
                .header("User-Agent", "RedirectBench/1.0")
                .timeout(Duration.ofSeconds(10)).build();
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<?>> workers = new ArrayList<>();
            for (int w = 0; w < concurrency; w++) {
                workers.add(pool.submit(() -> {
                    int i;
                    while ((i = next.getAndIncrement()) < n) {
                        long t0 = System.nanoTime();
                        try {
                            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                            if (status != 302) {
                                bad.incrementAndGet();
                            }
                        } catch (Exception e) {
                            bad.incrementAndGet();
                        }
                        lat[i] = System.nanoTime() - t0;
                    }
                    return null;
                }));
            }
            for (Future<?> f : workers) {
                f.get();
            }
        }
        return new Result(lat, bad.get());
    }

    static String createLink(HttpClient http, String base) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/links"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/bench\"}"))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(res.body());
        if (res.statusCode() != 201 || !m.find()) {
            throw new IllegalStateException("Could not create link: " + res.statusCode() + " " + res.body());
        }
        return m.group(1);
    }

    static double pct(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)] / 1e6;
    }
}
