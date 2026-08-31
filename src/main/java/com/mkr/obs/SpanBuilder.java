package com.mkr.obs;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/** Span 构建器（链式 attr/event），AutoCloseable 即 end；禁用态为 no-op。 */
public final class SpanBuilder implements AutoCloseable {

    private static final SpanBuilder NOOP = new SpanBuilder(null);

    private final Span span;

    SpanBuilder(Span span) {
        this.span = span;
    }

    static SpanBuilder noop() {
        return NOOP;
    }

    public SpanBuilder attr(String key, Object value) {
        if (span != null && value != null) {
            if (value instanceof Integer || value instanceof Long) {
                span.setAttribute(key, ((Number) value).longValue());
            } else if (value instanceof Double || value instanceof Float) {
                span.setAttribute(key, ((Number) value).doubleValue());
            } else if (value instanceof Boolean b) {
                span.setAttribute(key, b);
            } else {
                span.setAttribute(key, String.valueOf(value));
            }
        }
        return this;
    }

    public SpanBuilder event(String name) {
        if (span != null) {
            span.addEvent(name);
        }
        return this;
    }

    public SpanBuilder error(Throwable t) {
        if (span != null && t != null) {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR, t.getMessage());
        }
        return this;
    }

    public void end() {
        if (span != null) {
            span.end();
        }
    }

    @Override
    public void close() {
        end();
    }
}
