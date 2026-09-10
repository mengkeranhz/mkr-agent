package com.mkr.tools.builtin;

import com.mkr.core.RunContext;

/**
 * 高德 Key 取值（route_query / search_place / search_nearby 共享）：
 * 配置 tools.route-planner.amap-key 优先，其次环境变量 AMAP_KEY，两者皆无返回空串。
 */
final class AmapSupport {

    private AmapSupport() {
    }

    static String amapKey(RunContext ctx) {
        String fromCfg = ctx != null && ctx.config() != null ? ctx.config().tools.routePlannerAmapKey : null;
        if (fromCfg != null && !fromCfg.isBlank()) {
            return fromCfg.trim();
        }
        String env = System.getenv("AMAP_KEY");
        return env == null || env.isBlank() ? "" : env.trim();
    }
}
