package com.mkr.core;

/** 单次任务执行结果。 */
public record AgentResult(Status status, String answer, String reason, int rounds, double costUsd, long durationMs) {

    public enum Status {COMPLETED, MAX_ITERATIONS, ESCALATED, FAILED, CANCELLED}

    public static AgentResult completed(String answer, int rounds, double cost, long durationMs) {
        return new AgentResult(Status.COMPLETED, answer, null, rounds, cost, durationMs);
    }

    public boolean success() {
        return status == Status.COMPLETED;
    }
}
