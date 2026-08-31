package com.mkr.guard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 云沙箱（可选占位）：E2B 风格托管执行 API。POST {url}/execute
 * body {command, timeout_ms} → {exit_code, stdout, stderr}。未配置 endpoint 时明确报错。
 */
public final class CloudSandbox implements Sandbox {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String endpoint;
    private final String apiKey;

    public CloudSandbox(String endpoint, String apiKey) {
        this.endpoint = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    @Override
    public String type() {
        return "cloud";
    }

    @Override
    public ExecResult exec(String command, Path workdir, long timeoutMs) {
        if (endpoint.isBlank()) {
            return ExecResult.fail("cloud 沙箱未配置 tools.cloud-sandbox-url");
        }
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint + "/execute"))
                    .timeout(Duration.ofMillis(Math.max(timeoutMs, 10_000) + 5_000))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(com.mkr.util.Json.write(Map.of(
                            "command", command,
                            "workdir", workdir == null ? "" : workdir.toString(),
                            "timeout_ms", timeoutMs)), StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return new ExecResult(resp.statusCode(), "", "cloud 沙箱 HTTP " + resp.statusCode() + ": "
                        + resp.body(), false, System.currentTimeMillis() - start);
            }
            var node = com.mkr.util.Json.read(resp.body());
            return new ExecResult(
                    node.path("exit_code").asInt(1),
                    node.path("stdout").asText(""),
                    node.path("stderr").asText(""),
                    false, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new ExecResult(125, "", "cloud 沙箱调用失败: " + e.getMessage(), false,
                    System.currentTimeMillis() - start);
        }
    }
}
