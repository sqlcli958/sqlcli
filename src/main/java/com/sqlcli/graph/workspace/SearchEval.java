package com.sqlcli.graph.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 检索评估：对每个 case 跑一次 search，按期望的图谱对象 id 计算 Top1/Top5 命中率与 MRR。
 * 只依赖「query → 有序 id 列表」这个函数，不关心用的是实时引擎还是索引引擎。
 */
public final class SearchEval {

    private SearchEval() {
    }

    /** 一个评估用例：查询词 + 期望命中的图谱对象 id（任意一个命中即算命中）。 */
    public record EvalCase(String query, List<String> expect) {
    }

    /** 单个用例结果：bestRank 是期望 id 在结果里的最靠前名次（1 起），0 表示完全未命中。 */
    public record CaseResult(String query, List<String> expect, int bestRank,
            boolean top1, boolean top5, boolean hit) {
    }

    public record Report(List<CaseResult> results, double top1Rate, double top5Rate, double mrr) {

        public List<CaseResult> badcases() {
            return results.stream().filter(result -> !result.hit()).toList();
        }

        /** 全部 case 都有命中 → 退出码 0；存在完全未命中的 case → 1（CI 用）。 */
        public boolean allHit() {
            return results.stream().allMatch(CaseResult::hit);
        }
    }

    public static Report evaluate(List<EvalCase> cases, Function<String, List<String>> search) {
        List<CaseResult> results = new ArrayList<>();
        int top1 = 0;
        int top5 = 0;
        double reciprocalSum = 0;
        for (EvalCase evalCase : cases) {
            List<String> ids = search.apply(evalCase.query());
            int bestRank = bestRank(ids, evalCase.expect());
            boolean hit = bestRank > 0;
            boolean isTop1 = bestRank == 1;
            boolean isTop5 = hit && bestRank <= 5;
            if (isTop1) top1++;
            if (isTop5) top5++;
            if (hit) reciprocalSum += 1.0 / bestRank;
            results.add(new CaseResult(evalCase.query(), evalCase.expect(), bestRank, isTop1, isTop5, hit));
        }
        int total = cases.size();
        return new Report(results,
                total == 0 ? 0 : (double) top1 / total,
                total == 0 ? 0 : (double) top5 / total,
                total == 0 ? 0 : reciprocalSum / total);
    }

    private static int bestRank(List<String> ids, List<String> expect) {
        for (int i = 0; i < ids.size(); i++) {
            for (String expected : expect) {
                if (ids.get(i) != null && ids.get(i).equalsIgnoreCase(expected)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }
}
