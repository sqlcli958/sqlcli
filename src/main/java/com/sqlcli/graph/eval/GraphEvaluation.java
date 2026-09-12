package com.sqlcli.graph.eval;

import java.util.List;
import java.util.Map;

/**
 * 一次图谱评估的产出：一批 finding + 几个覆盖率指标。
 *
 * <p>不含 id / 时间 / revision——那些是<b>存储</b>要的，由调用方在落库时补上，
 * 评估本身是纯函数（同一份图谱跑两次结果必须一样，否则没法比对两次评估）。
 *
 * @param metrics  覆盖率，0~1 的比例，<b>只报告不判定</b>：「表描述覆盖 70% 算不算够」
 *                 没有普适答案，设阈值就是瞎设。它存在是为了画趋势
 * @param findings 硬错误与改进项，按探针分组顺序
 */
public record GraphEvaluation(Map<String, Double> metrics, List<GraphFinding> findings) {

    public long errorCount() {
        return findings.stream().filter(f -> f.severity() == GraphFinding.Severity.error).count();
    }

    public long warningCount() {
        return findings.size() - errorCount();
    }

    /** 只有硬错误判失败。覆盖率再低也不失败——它没有普适阈值。 */
    public boolean failed() {
        return errorCount() > 0;
    }

    public String status() {
        return failed() ? "fail" : "pass";
    }
}
