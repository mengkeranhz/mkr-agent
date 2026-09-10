package com.mkr.tools.route;

/**
 * 高德 API 非成功响应（status != "1"）。临时性失败（配额/网络/Key 无效等）
 * 由 {@link RouteFetcher} 降级浏览器兜底；业务性失败见 {@link AmapBusinessException}。
 */
class AmapApiException extends RuntimeException {

    AmapApiException(String message) {
        super(message);
    }
}
