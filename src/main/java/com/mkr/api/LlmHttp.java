package com.mkr.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mkr.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * LLM HTTP 公共设施：JSON POST、SSE 流式读取（带空闲看门狗）、HTTP 错误映射。
 *
 * <p>看门狗：SSE 读取跑在虚拟线程上，主线程按 idleTimeoutMs 轮询；
 * 超过阈值未收到任何数据则 interrupt 读取线程并抛 {@link RetryableLlmException}，
 * 由上层 Watchdog/RetryPolicy 重试一次（对齐 spec：流空闲 &gt; 60s 视为卡死）。</p>
 */
public final class LlmHttp {

    private LlmHttp() {
    }

    @FunctionalInterface
    public interface SseConsumer {
        void accept(JsonNode event) throws Exception;
    }

    /** 非流式 JSON POST，返回响应体。网络/超时异常统一映射为可重试。 */
    public static String postJson(HttpClient client, String url, java.util.Map<String, String> headers,
                                  Object body, long timeoutMs) {
        HttpRequest req = baseRequest(url, headers, body).timeout(Duration.ofMillis(timeoutMs)).build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw httpError(resp.statusCode(), resp.body());
            }
            return resp.body();
        } catch (IOException e) {
            throw new RetryableLlmException("LLM 网络错误: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableLlmException("LLM 调用被中断", e);
        }
    }

    /** SSE 流式 POST。data 行解析为 JSON 逐个回调；[DONE] 或流自然结束返回。 */
    public static void sse(HttpClient client, String url, java.util.Map<String, String> headers,
                           Object body, long idleTimeoutMs, SseConsumer onEvent) {
        HttpRequest req = baseRequest(url, headers, body)
                .timeout(Duration.ofMillis(Math.max(idleTimeoutMs * 4, 300_000)))
                .build();
        java.util.concurrent.atomic.AtomicLong lastTouch = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("mkr-sse-reader").unstarted(() -> {
            try {
                HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() / 100 != 2) {
                    String errBody = readCap(resp.body(), 2000);
                    done.completeExceptionally(httpError(resp.statusCode(), errBody));
                    return;
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lastTouch.set(System.currentTimeMillis());
                        if (line.isEmpty() || line.startsWith(":")) {
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();
                            if ("[DONE]".equals(data)) {
                                done.complete(null);
                                return;
                            }
                            JsonNode n = Json.read(data);
                            if (n != null) {
                                onEvent.accept(n);
                            }
                        }
                    }
                }
                done.complete(null); // 流自然结束（部分 provider 不发 [DONE]）
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        reader.start();
        try {
            while (true) {
                try {
                    done.get(idleTimeoutMs, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException te) {
                    if (System.currentTimeMillis() - lastTouch.get() > idleTimeoutMs) {
                        reader.interrupt();
                        throw new RetryableLlmException("LLM 流空闲超时（>" + idleTimeoutMs + "ms），判定卡死");
                    }
                } catch (ExecutionException ee) {
                    rethrow(ee.getCause());
                    return;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RetryableLlmException("LLM 流读取被中断", ie);
        }
    }

    private static HttpRequest.Builder baseRequest(String url, java.util.Map<String, String> headers, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return b;
    }

    /** 状态码 → 异常类型：429/5xx 可重试；4xx 不可重试。 */
    public static RuntimeException httpError(int status, String body) {
        String snippet = body == null ? "" : body.replaceAll("\\s+", " ");
        if (snippet.length() > 400) {
            snippet = snippet.substring(0, 400) + "…";
        }
        String msg = "LLM HTTP " + status + (snippet.isBlank() ? "" : ": " + snippet);
        if (status == 429 || status >= 500) {
            return new RetryableLlmException(msg);
        }
        return new NonRetryableLlmException(msg);
    }

    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException rt) {
            throw rt;
        }
        throw new RetryableLlmException("LLM 流错误: " + t.getMessage(), t);
    }

    private static String readCap(java.io.InputStream in, int cap) {
        try {
            byte[] buf = new byte[cap];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
