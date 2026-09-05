package com.mkr.tools.route;

/**
 * 单条路线方案（设计方案 §七 数据模型）。耗时/距离保留原始数值（秒/米）供评分，
 * 文本形式按需派生。
 */
public final class RoutePlan {

    /** car | bus | walk。 */
    private final String mode;
    private final long durationSec;
    private final long distanceMeters;
    /** 费用（元，可空）：驾车=过路费，公交=票价。 */
    private final Double costYuan;
    /** 换乘次数（公交）。 */
    private final int transfers;
    /** 路线概要（道路/线路序列）。 */
    private final String description;
    /** amap-api（官方 API）| amap-web（浏览器渲染页解析）。 */
    private final String source;
    /** RouteScorer 综合评分。 */
    private double score;

    public RoutePlan(String mode, long durationSec, long distanceMeters, Double costYuan,
                     int transfers, String description, String source) {
        this.mode = mode;
        this.durationSec = durationSec;
        this.distanceMeters = distanceMeters;
        this.costYuan = costYuan;
        this.transfers = transfers;
        this.description = description == null ? "" : description;
        this.source = source;
    }

    public static String modeLabel(String mode) {
        return switch (mode) {
            case "car" -> "驾车";
            case "bus" -> "公交";
            case "walk" -> "步行";
            default -> mode;
        };
    }

    public static String modeIcon(String mode) {
        return switch (mode) {
            case "car" -> "🚗";
            case "bus" -> "🚇";
            case "walk" -> "🚶";
            default -> "🗺️";
        };
    }

    public String mode() {
        return mode;
    }

    public long durationSec() {
        return durationSec;
    }

    public long distanceMeters() {
        return distanceMeters;
    }

    public Double costYuan() {
        return costYuan;
    }

    public int transfers() {
        return transfers;
    }

    public String description() {
        return description;
    }

    public String source() {
        return source;
    }

    public double score() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String durationText() {
        long min = Math.round(durationSec / 60.0);
        if (min < 60) {
            return min + "分钟";
        }
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? h + "小时" : h + "小时" + m + "分钟";
    }

    public String distanceText() {
        if (distanceMeters >= 1000) {
            return String.format("%.1f公里", distanceMeters / 1000.0);
        }
        return distanceMeters + "米";
    }

    /** 摘要（推荐行用）：(59分钟, 38.5公里)。 */
    public String summary() {
        return "(" + durationText() + ", " + distanceText() + ")";
    }
}
