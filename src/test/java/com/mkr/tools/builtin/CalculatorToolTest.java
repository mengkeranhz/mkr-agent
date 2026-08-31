package com.mkr.tools.builtin;

import com.mkr.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自研 AST 计算器：四则/幂/函数/常量/错误路径。 */
class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    private String eval(String expr) throws Exception {
        ToolResult r = tool.run(java.util.Map.of("expression", expr), null);
        assertTrue(r.success(), expr + " => " + r.render());
        return r.output();
    }

    @Test
    void arithmetic() throws Exception {
        assertEquals("1+2 = 3", eval("1+2"));
        assertEquals("(1+2)*3 = 9", eval("(1+2)*3"));
        assertEquals("2^10 = 1024", eval("2^10"));
        assertEquals("10 % 3 = 1", eval("10 % 3"));
        assertEquals("-5 + 3 = -2", eval("-5 + 3"));
        assertEquals("2^-1 = 0.5", eval("2^-1")); // 幂右结合 + 一元负号
    }

    @Test
    void functionsAndConstants() throws Exception {
        assertEquals("sqrt(9) = 3", eval("sqrt(9)"));
        assertEquals("abs(-3.5) = 3.5", eval("abs(-3.5)"));
        assertTrue(eval("sin(0)").endsWith("= 0"));
        assertTrue(eval("pi").contains("3.14"));
    }

    @Test
    void precedence() throws Exception {
        assertEquals("2+3*4 = 14", eval("2+3*4"));
        assertEquals("(2+3)*4 = 20", eval("(2+3)*4"));
    }

    @Test
    void errors() throws Exception {
        assertFalse(tool.run(java.util.Map.of("expression", "1/0"), null).success());
        assertFalse(tool.run(java.util.Map.of("expression", "sqrt(-1)"), null).success());
        assertFalse(tool.run(java.util.Map.of("expression", "1 + "), null).success());
        assertFalse(tool.run(java.util.Map.of("expression", "foo(1)"), null).success());
        assertFalse(tool.run(java.util.Map.of("expression", "rm -rf /"), null).success());
    }
}
