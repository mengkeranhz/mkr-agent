package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * Calculator（calculator）：自研递归下降 AST 求值（数字/四则/括号/幂/函数/常量），
 * 不用 JShell（安全、无编译开销）。
 */
@AgentTool(name = "calculator",
        description = "精确计算数学表达式（+ - * / % ^、括号、函数、pi/e）。Use when: 需要精确算术；Don't use when: 复杂处理用 code_interpreter。",
        risk = Risk.LOW)
public final class CalculatorTool implements Tool {

    @Override
    public List<ToolParam> parameters() {
        return List.of(new ToolParam("expression", "string", "数学表达式，如 (1+2)*3 / sqrt(2) / 2^10", true));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        Object expr = params.get("expression");
        if (expr == null || String.valueOf(expr).isBlank()) {
            return ToolResult.error("INVALID_ARGS", "缺少 expression 参数");
        }
        try {
            double value = new Parser(String.valueOf(expr)).parse();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return ToolResult.error("MATH_ERROR", "结果非有限数: " + value);
            }
            String shown = value == Math.rint(value) && Math.abs(value) < 1e15
                    ? String.valueOf((long) value) : String.valueOf(value);
            return ToolResult.ok(expr + " = " + shown);
        } catch (ArithmeticException e) {
            return ToolResult.error("MATH_ERROR", e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("PARSE_ERROR", "表达式解析失败: " + e.getMessage());
        }
    }

    /** 递归下降解析器：expr := term (('+'|'-') term)* ; term := factor (('*'|'/'|'%') factor)* ; factor := unary ('^' factor)? */
    static final class Parser {
        private final String s;
        private int pos = -1;
        private int ch;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            next();
            double v = expr();
            skip();
            if (ch != -1) {
                throw new IllegalArgumentException("位置 " + pos + " 有未消费字符 '" + (char) ch + "'");
            }
            return v;
        }

        private void next() {
            ch = ++pos < s.length() ? s.charAt(pos) : -1;
        }

        private void skip() {
            while (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                next();
            }
        }

        private double expr() {
            double v = term();
            while (true) {
                skip();
                if (ch == '+') {
                    next();
                    v += term();
                } else if (ch == '-') {
                    next();
                    v -= term();
                } else {
                    return v;
                }
            }
        }

        private double term() {
            double v = factor();
            while (true) {
                skip();
                if (ch == '*') {
                    next();
                    v *= factor();
                } else if (ch == '/') {
                    next();
                    double d = factor();
                    if (d == 0) {
                        throw new ArithmeticException("除以零");
                    }
                    v /= d;
                } else if (ch == '%') {
                    next();
                    double d = factor();
                    if (d == 0) {
                        throw new ArithmeticException("模零");
                    }
                    v %= d;
                } else {
                    return v;
                }
            }
        }

        private double factor() {
            double base = unary();
            skip();
            if (ch == '^') {
                next();
                return Math.pow(base, factor()); // 右结合
            }
            return base;
        }

        private double unary() {
            skip();
            if (ch == '-') {
                next();
                return -unary();
            }
            if (ch == '+') {
                next();
                return unary();
            }
            return primary();
        }

        private double primary() {
            skip();
            if (ch == '(') {
                next();
                double v = expr();
                skip();
                if (ch != ')') {
                    throw new IllegalArgumentException("缺少右括号");
                }
                next();
                return v;
            }
            if (Character.isLetter(ch)) {
                return ident();
            }
            int start = pos;
            while ((ch >= '0' && ch <= '9') || ch == '.') {
                next();
            }
            if (start == pos) {
                throw new IllegalArgumentException("位置 " + pos + " 期望数字/括号/标识符");
            }
            String num = s.substring(start, pos);
            if (num.indexOf('.') != num.lastIndexOf('.')) {
                throw new IllegalArgumentException("非法数字: " + num);
            }
            return Double.parseDouble(num);
        }

        private double ident() {
            int start = pos;
            while (Character.isLetterOrDigit(ch)) {
                next();
            }
            String name = s.substring(start, pos).toLowerCase();
            switch (name) {
                case "pi":
                    return Math.PI;
                case "e":
                    return Math.E;
                default:
            }
            skip();
            if (ch != '(') {
                throw new IllegalArgumentException("未知常量: " + name);
            }
            next();
            double arg = expr();
            skip();
            if (ch != ')') {
                throw new IllegalArgumentException("函数 " + name + " 缺少右括号");
            }
            next();
            return switch (name) {
                case "sin" -> Math.sin(arg);
                case "cos" -> Math.cos(arg);
                case "tan" -> Math.tan(arg);
                case "sqrt" -> {
                    if (arg < 0) {
                        throw new ArithmeticException("sqrt 负数: " + arg);
                    }
                    yield Math.sqrt(arg);
                }
                case "log" -> Math.log10(arg);
                case "ln" -> Math.log(arg);
                case "abs" -> Math.abs(arg);
                case "exp" -> Math.exp(arg);
                case "floor" -> Math.floor(arg);
                case "ceil" -> Math.ceil(arg);
                case "round" -> (double) Math.round(arg);
                default -> throw new IllegalArgumentException("未知函数: " + name);
            };
        }
    }
}
