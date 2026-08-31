package com.mkr.obs;

import java.util.Map;

/**
 * 特性开关（消融实验）：运行时 YAML features.* + 环境变量 MKR_FEATURE_&lt;NAME&gt; 覆盖。
 * 大特性各一开关：status-bar / compression / sidecar / progressive-tools / otel /
 * memory-hybrid / subagents。
 */
public final class FeatureFlag {

    private final Map<String, Boolean> flags;

    public FeatureFlag(Map<String, Boolean> flags) {
        this.flags = flags == null ? Map.of() : flags;
    }

    public boolean isEnabled(String name) {
        String env = System.getenv("MKR_FEATURE_" + name.toUpperCase().replace('-', '_'));
        if (env != null) {
            return env.equalsIgnoreCase("true") || env.equals("1") || env.equalsIgnoreCase("yes");
        }
        return flags.getOrDefault(name, true);
    }

    public Map<String, Boolean> all() {
        return flags;
    }
}
