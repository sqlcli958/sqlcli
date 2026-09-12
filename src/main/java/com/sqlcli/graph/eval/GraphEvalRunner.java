package com.sqlcli.graph.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.runstate.RunStateStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 跑一次图谱评估并落库。**CLI 和 Web UI 共用这一份**。
 *
 * <h2>为什么必须共用</h2>
 * 评估页原来是只读的，理由写在 {@code CLAUDE.md} 里：「页面上没有『跑一次』——开出第二条
 * 产出路径，两边结果迟早对不上」。那条担心针对的是**两套实现**，不是两个入口：
 * 同一个 {@link GraphEvaluator} 跑同一份图谱、落同一张表、写同一个 {@code source}，
 * 算不出两个结果。所以 UI 要加按钮的前提是把这段逻辑提出来共用——
 * 在控制器里再抄一遍落库代码，才是那条纪律真正要挡的东西。
 *
 * <p>评估本身是纯函数（{@link GraphEvaluation} 的类注释写了原因：同一份图谱跑两次结果
 * 必须一样，否则没法比对两次评估）。id / 时间 / revision 这些存储字段在这里补上。
 */
public final class GraphEvalRunner {

    /** 图谱评估的来源标记。趋势图只连这个来源的点——见 {@link RunStateStore.GraphEvaluationRow}。 */
    public static final String SOURCE_EVAL = "eval";

    private GraphEvalRunner() {
    }

    /** 一次评估的落库结果：评估本身 + 它在运行库里的 id。 */
    public record Result(String evaluationId, GraphEvaluation evaluation, long revision,
                         long startedAt, long elapsedMs) {
    }

    /**
     * 跑一次评估并写进运行库。
     *
     * @param alias     数据源别名，评估记录按它归属
     * @param workspace 被评估的图谱，调用方负责已经加载好
     */
    public static Result runAndSave(String alias, GraphWorkspace workspace) throws IOException {
        long startedAt = System.currentTimeMillis();
        GraphEvaluation evaluation = new GraphEvaluator().evaluate(workspace);
        String evaluationId = "eval-" + UUID.randomUUID();
        long revision = workspace.getManifest().getRevision();

        // 交互探针在这里追加，**不能塞进 GraphEvaluator**：那个类是纯函数（同一份图谱
        // 跑两次结果必须一样，否则两次评估没法比），而交互记录会随时间变。
        // 产出一律 warning，所以 evaluation.status() 和硬错误数仍然只由图谱本身决定。
        RunStateStore runState = new RunStateStore();
        List<GraphFinding> findings = new ArrayList<>(evaluation.findings());
        findings.addAll(InteractionProbes.run(runState, alias,
                runState.lastEvaluationAt(alias, SOURCE_EVAL)));

        // 返回合并后的那份，不是纯评估那份：接口报的 warningCount 必须跟落库的一致，
        // 否则「页面上说 3 条、点进去有 5 条」——这种对不上没人会去查是哪一半错了。
        // status 仍由 evaluation 决定（交互发现全是 warning，不影响成败）。
        GraphEvaluation merged = new GraphEvaluation(evaluation.metrics(), List.copyOf(findings));
        save(evaluationId, alias, SOURCE_EVAL, revision, startedAt, evaluation.status(),
                findings, new ObjectMapper().writeValueAsString(evaluation.metrics()));
        return new Result(evaluationId, merged, revision, startedAt,
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 评估结果落运行库。findings 独立成行，能排序能分页。
     *
     * <p>{@code source} 是参数而不是写死 {@code eval}：{@code schema data-quality} 也走这里，
     * 它落 {@code source = data-quality}、{@code metricsJson = null}——数据层没有覆盖率
     * 这回事，硬凑一个百分比就是虚荣指标。
     */
    public static void save(String evaluationId, String alias, String source, long revision,
            long startedAt, String status, List<GraphFinding> findings, String metricsJson)
            throws IOException {
        List<RunStateStore.GraphFindingRow> rows = new ArrayList<>();
        for (int i = 0; i < findings.size(); i++) {
            GraphFinding finding = findings.get(i);
            rows.add(new RunStateStore.GraphFindingRow(i, finding.probe(), finding.severity().name(),
                    finding.targetId(), finding.message(), finding.remediation()));
        }
        long errors = findings.stream()
                .filter(finding -> finding.severity() == GraphFinding.Severity.error).count();
        new RunStateStore().saveGraphEvaluation(new RunStateStore.GraphEvaluationRow(
                evaluationId, alias, source, status, revision, startedAt,
                System.currentTimeMillis() - startedAt, (int) errors, findings.size() - (int) errors,
                metricsJson), rows);
    }
}
