package com.mkr.verify;

/** 验证结果：失败信息（文件+行+原因）回灌模型自纠。 */
public record VerifyResult(boolean passed, String message) {

    public static VerifyResult pass() {
        return new VerifyResult(true, "ok");
    }

    public static VerifyResult fail(String message) {
        return new VerifyResult(false, message);
    }

    public String render() {
        return passed ? "[VERIFY ok]" : "[VERIFY FAILED] " + message;
    }
}
