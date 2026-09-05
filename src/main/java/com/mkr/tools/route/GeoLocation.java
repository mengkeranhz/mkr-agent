package com.mkr.tools.route;

/**
 * 地名解析结果（设计方案 §七 数据模型）。经纬度可空：无 API Key 的浏览器降级路径
 * 只有名称（由高德网页在路线页内自行解析坐标）。
 */
public final class GeoLocation {

    /** 标准化短名，如「瓶窑镇」。 */
    private final String name;
    /** 完整格式化地址，如「浙江省杭州市余杭区瓶窑镇」。 */
    private final String formatted;
    private final String province;
    private final String city;
    private final String district;
    private final double lng;
    private final double lat;
    /** 0~1，解析置信度（GeocodeResolver 计算）。 */
    private double confidence;

    public GeoLocation(String name, String formatted, String province, String city,
                       String district, Double lng, Double lat) {
        this.name = name == null ? "" : name;
        this.formatted = formatted == null || formatted.isBlank() ? this.name : formatted;
        this.province = province;
        this.city = city;
        this.district = district;
        this.lng = lng == null ? 0 : lng;
        this.lat = lat == null ? 0 : lat;
    }

    /** 仅名称（无 Key 降级）：无坐标，路线页内由高德解析。 */
    public static GeoLocation nameOnly(String name) {
        return new GeoLocation(name, name, null, null, null, null, null);
    }

    public String name() {
        return name;
    }

    public String formatted() {
        return formatted;
    }

    public String province() {
        return province;
    }

    public String city() {
        return city;
    }

    public String district() {
        return district;
    }

    public double lng() {
        return lng;
    }

    public double lat() {
        return lat;
    }

    public double confidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    /** "lng,lat"（高德坐标系）；无坐标返回 null。 */
    public String lngLat() {
        return lng != 0 && lat != 0 ? lng + "," + lat : null;
    }

    /** 展示用：区县@城市 或仅名称。 */
    public String label() {
        if (district != null && !district.isBlank() && city != null && !city.isBlank()) {
            return name + "（" + city + (district.contains(city) ? "" : "·" + district) + "）";
        }
        return formatted;
    }
}
