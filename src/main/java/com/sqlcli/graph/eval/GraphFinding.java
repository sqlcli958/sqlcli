package com.sqlcli.graph.eval;

/**
 * 一条图谱评估发现：哪个探针、多严重、哪个对象、怎么修。
 *
 * <p><b>remediation 不是装饰。</b>评估报告的用处是「一份排好序、可以直接交给 agent
 * 去执行的工作队列」——只报「这里不对」而不给修法，人和 agent 都得再去查一遍怎么改，
 * 那份报告就会躺着没人动。
 */
public record GraphFinding(
        String probe,
        Severity severity,
        String targetId,
        String message,
        String remediation) {

    public enum Severity {
        /** 硬错误：判定这次评估失败，退出码非 0 */
        error,
        /** 改进项：只报告，不判失败 */
        warning
    }

    public static GraphFinding error(String probe, String targetId, String message, String remediation) {
        return new GraphFinding(probe, Severity.error, targetId, message, remediation);
    }

    public static GraphFinding warning(String probe, String targetId, String message, String remediation) {
        return new GraphFinding(probe, Severity.warning, targetId, message, remediation);
    }
}
