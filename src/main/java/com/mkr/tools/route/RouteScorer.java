package com.mkr.tools.route;

import java.util.Comparator;
import java.util.List;

/**
 * 路线筛选与排序（设计方案 §二.4）：时间/距离/费用/便捷四维加权。
 * 各维按集合内最优值归一到 0~100（避免「100/(1+分钟)」量纲失衡），偏好可调权重。
 */
public final class RouteScorer {

    private static final double W_TIME = 0.4;
    private static final double W_DISTANCE = 0.2;
    private static final double W_COST = 0.2;
    private static final double W_CONVENIENCE = 0.2;

    /** prefer → 权重（fastest/shortest/cheapest 单维全重）。 */
    public List<RoutePlan> scoreAll(List<RoutePlan> plans, String prefer) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        long minDur = plans.stream().mapToLong(RoutePlan::durationSec).min().orElse(1);
        long minDist = plans.stream().mapToLong(RoutePlan::distanceMeters).filter(d -> d > 0).min().orElse(0);
        double minCost = plans.stream().map(RoutePlan::costYuan)
                .filter(c -> c != null && c > 0).mapToDouble(Double::doubleValue).min().orElse(0);
        double[] w = weights(prefer);
        for (RoutePlan p : plans) {
            double timeScore = 100.0 * minDur / Math.max(1, p.durationSec());
            double distScore = minDist <= 0 || p.distanceMeters() <= 0 ? 80
                    : 100.0 * minDist / p.distanceMeters();
            double costScore = p.costYuan() == null || p.costYuan() <= 0 ? 100
                    : (minCost <= 0 ? 60 : 100.0 * minCost / p.costYuan());
            double convScore = Math.max(0, 100 - 15 * p.transfers());
            p.setScore(w[0] * timeScore + w[1] * distScore + w[2] * costScore + w[3] * convScore);
        }
        return plans;
    }

    public RoutePlan best(List<RoutePlan> plans) {
        return plans.stream()
                .max(Comparator.comparingDouble(RoutePlan::score)
                        .thenComparing(Comparator.comparingLong(RoutePlan::durationSec).reversed()))
                .orElse(null);
    }

    private static double[] weights(String prefer) {
        return switch (prefer == null ? "balanced" : prefer) {
            case "fastest" -> new double[]{1, 0, 0, 0};
            case "shortest" -> new double[]{0, 1, 0, 0};
            case "cheapest" -> new double[]{0, 0, 1, 0};
            default -> new double[]{W_TIME, W_DISTANCE, W_COST, W_CONVENIENCE};
        };
    }
}
