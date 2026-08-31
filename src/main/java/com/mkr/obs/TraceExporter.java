package com.mkr.obs;

import com.mkr.util.Json;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * OpenTelemetry Trace 导出（OTLP HTTP）。无 endpoint 静默关闭（no-op span）。
 * Span 约定（OpenInference）：任务=root；LLM=llm（provider/model/token.usage）；
 * 工具=tool（name/arguments/output）；检索=retriever。
 * 失败轨迹（脱敏）导出 eval/regression/ 回流评估集。
 */
public final class TraceExporter implements AutoCloseable {

    private final SdkTracerProvider provider;
    private final Tracer tracer;
    private final Path regressionDir;

    private TraceExporter(SdkTracerProvider provider, Tracer tracer, Path regressionDir) {
        this.provider = provider;
        this.tracer = tracer;
        this.regressionDir = regressionDir;
    }

    public static TraceExporter disabled(Path regressionDir) {
        return new TraceExporter(null, null, regressionDir);
    }

    /** otlp-endpoint 为空 → disabled；4317(gRPC) 自动换 4318 HTTP /v1/traces。 */
    public static TraceExporter create(String endpoint, Path regressionDir) {
        if (endpoint == null || endpoint.isBlank()) {
            return disabled(regressionDir);
        }
        try {
            String url = endpoint.trim();
            if (url.endsWith(":4317")) {
                url = url.substring(0, url.length() - 5) + ":4318";
            }
            if (!url.endsWith("/v1/traces")) {
                url = url.replaceAll("/+$", "") + "/v1/traces";
            }
            OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder().setEndpoint(url).build();
            Resource resource = Resource.getDefault().merge(
                    Resource.builder().put(AttributeKey.stringKey("service.name"), "mkr-agent").build());
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build();
            OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            return new TraceExporter(provider, otel.getTracer("mkr-agent"), regressionDir);
        } catch (Exception | NoClassDefFoundError e) {
            System.err.println("[mkr] OTel 初始化失败，trace 关闭: " + e.getMessage());
            return disabled(regressionDir);
        }
    }

    public boolean enabled() {
        return tracer != null;
    }

    public SpanBuilder span(String name) {
        return span(name, null);
    }

    public SpanBuilder span(String name, Span parent) {
        if (tracer == null) {
            return SpanBuilder.noop();
        }
        Span span = (parent == null ? tracer.spanBuilder(name) : tracer.spanBuilder(name).setParent(
                io.opentelemetry.context.Context.current().with(parent)))
                .setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .startSpan();
        return new SpanBuilder(span);
    }

    /** 失败轨迹回流：写 eval/regression/&lt;ts&gt;-&lt;id&gt;.json（脱敏：去掉 api key 等敏感字段）。 */
    public Path exportRegression(String sessionId, Map<String, Object> sanitizedPayload) {
        try {
            Files.createDirectories(regressionDir);
            String ts = LocalDateTime.now().toString().replace(":", "");
            Path file = regressionDir.resolve(ts + "-" + sessionId + ".json");
            Files.writeString(file, Json.pretty(sanitizedPayload));
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (provider != null) {
            provider.close();
        }
    }
}
