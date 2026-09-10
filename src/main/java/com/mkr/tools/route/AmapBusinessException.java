package com.mkr.tools.route;

/**
 * 高德 API 业务性失败（步行超出可规划距离、公交缺城市参数等）。
 * 此类失败重试或降级浏览器兜底均无意义，{@link RouteFetcher} 直接快速失败并提示。
 */
final class AmapBusinessException extends AmapApiException {

    AmapBusinessException(String message) {
        super(message);
    }
}
