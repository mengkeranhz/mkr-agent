package com.mkr.obs;

import com.mkr.api.LlmResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 成本追踪：每次 LLM 调用累计 tokens×单价（USD / 1M tokens），/cost 展示。
 * 单价表来自 config obs.prices，可覆盖内置默认。
 */
public final class CostTracker {

    private final Map<String, double[]> prices = new ConcurrentHashMap<>();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final Map<String, AtomicLong> perModel = new ConcurrentHashMap<>();

    public CostTracker(Map<String, double[]> defaultPrices) {
        if (defaultPrices != null) {
            prices.putAll(defaultPrices);
        }
    }

    public void setPrices(Map<String, double[]> overrides) {
        if (overrides != null) {
            prices.putAll(overrides);
        }
    }

    public void track(LlmResponse.Usage usage, String model) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(usage.promptTokens());
        completionTokens.addAndGet(usage.completionTokens());
        calls.incrementAndGet();
        perModel.computeIfAbsent(model == null ? "unknown" : model, k -> new AtomicLong())
                .addAndGet(usage.total());
    }

    public double costUsd(long prompt, long completion, String model) {
        double[] price = prices.get(model);
        if (price == null) {
            // 前缀匹配（glm-4-plus-xxx 之类）
            price = prices.entrySet().stream()
                    .filter(e -> model != null && model.startsWith(e.getKey()))
                    .findFirst().map(Map.Entry::getValue).orElse(null);
        }
        if (price == null) {
            return 0;
        }
        return prompt / 1_000_000.0 * price[0] + completion / 1_000_000.0 * price[1];
    }

    public double totalUsd() {
        double total = 0;
        for (Map.Entry<String, AtomicLong> e : perModel.entrySet()) {
            // 按模型粗略分摊（无法拆分 in/out 时按 1:1）
            double[] price = prices.get(e.getKey());
            if (price != null) {
                total += e.getValue().get() / 2.0 / 1_000_000.0 * (price[0] + price[1]);
            }
        }
        return total;
    }

    public long totalTokens() {
        return promptTokens.get() + completionTokens.get();
    }

    public long calls() {
        return calls.get();
    }

    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("LLM 调用: ").append(calls.get())
                .append("  tokens: ").append(totalTokens())
                .append(" (in ").append(promptTokens.get())
                .append(" / out ").append(completionTokens.get()).append(")\n");
        if (perModel.isEmpty()) {
            sb.append("（尚无调用）");
        } else {
            perModel.forEach((m, t) -> {
                double[] price = prices.get(m);
                sb.append("- ").append(m).append(": ").append(t.get()).append(" tok");
                if (price == null) {
                    sb.append("（未配置单价，按 $0 计）");
                }
                sb.append('\n');
            });
            sb.append("估算成本: $").append(String.format("%.4f", totalUsd()));
        }
        return sb.toString();
    }
}
