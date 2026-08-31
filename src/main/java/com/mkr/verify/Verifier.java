package com.mkr.verify;

import com.mkr.core.RunContext;
import com.mkr.tools.ToolResult;

/** 验证器：工具产出的事实校验（编译/测试/完成判定）。 */
public interface Verifier {

    VerifyResult verify(ToolResult result, RunContext ctx);

    String name();
}
