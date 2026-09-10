package com.mkr.tools.route;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 高德 Web 服务 HTTP 调用兜底：客户端限流 + 瞬态错误退避重试。
 *
 * <p>免费/个人 Key 的 Web 服务 QPS 通常只有 3，而一次 route_query 调用会连续请求
 * place/text ×2 + direction ×1~3，同秒内即可能触发 {@code CUQPS_HAS_EXCEEDED_THE_LIMIT}。
 * 两层防护：① {@link #throttle()} 保证相邻请求至少间隔 {@value #MIN_INTERVAL_MS}ms（≈2.8 QPS）；
 * ② 命中限流/网络抖动等瞬态错误时退避重试，重试耗尽抛网络异常或返回限流响应体，
 * 交由调用方降级（RouteFetcher 浏览器兜底 / GeocodeResolver 空候选）。</p>
 */
final class AmapHttp {

    /** 相邻请求最小间隔（ms）：免费 Key QPS=3 留余量（≈2.8 QPS）。 */
    private static final long MIN_INTERVAL_MS = 350;
    /** 单请求总尝试次数（含首次，之后最多 2 次退避重试）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 退避基数（ms），按尝试次数线性递增（QPS 窗口约 1s，线性足够）。 */
    private static final long RETRY_BASE_MS = 500;

    private static final Object LOCK = new Object();
    private static long lastRequestAt = 0;

    private AmapHttp() {
    }

    /** GET 并返回响应体；瞬态错误退避重试，重试耗尽抛网络异常或返回限流响应体供调用方判定。 */
    static String get(HttpClient http, String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String body = resp.body();
                if (attempt < MAX_ATTEMPTS && isTransientLimit(body)) {
                    backoff(attempt);
                    continue;
                }
                return body;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("高德请求失败");
    }

    /** 高德限流/频率类错误（瞬态，退避后可自愈）；其余错误（参数/Key/日配额）不重试。 */
    private static boolean isTransientLimit(String body) {
        if (body == null) {
            return false;
        }
        return body.contains("QPS_HAS_EXCEEDED")          // CUQPS_HAS_EXCEEDED_THE_LIMIT 等
                || body.contains("VISIT_TOO_FREQUENTLY"); // 访问过于频繁
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 跨调用共享的限流闸：保证相邻请求至少间隔 {@value #MIN_INTERVAL_MS}ms。 */
    private static void throttle() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long wait = MIN_INTERVAL_MS - (now - lastRequestAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }
}
