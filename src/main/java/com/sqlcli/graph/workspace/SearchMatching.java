package com.sqlcli.graph.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 两个搜索引擎（实时 {@link WorkspaceSearchEngine} 与索引
 * {@link com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine}）共用的切词与打分。
 *
 * <p>中文没有空格，整串 substring 匹配下「买家 手机」谁也命中不了：查询先按 CJK bigram 切分
 * （买家手机 → 买家/家手/手机），再让每个词条各自去所有字段找最佳命中，按覆盖率加权求和。
 * 单词查询走的还是原来的整串匹配，行为不变。
 *
 * <p>ponytail: 纯 substring + 固定权重，没有 BM25/IDF；语料是一个库的表名列名，量级几万，
 * 够用。真要按词频区分冷热字段时再上倒排。
 */
public final class SearchMatching {

    private SearchMatching() {
    }

    /** 系统表降权系数：不排除（还要能搜到），但排到同分业务表后面。 */
    public static final double SYSTEM_TABLE_FACTOR = 0.5;

    /**
     * 实时引擎（{@link WorkspaceSearchEngine}）与索引引擎
     * （{@link com.sqlcli.graph.workspace.index.WorkspaceIndexedSearchEngine}）共用的字段权重。
     * 两边各自还有的 qualifiedName / alias / tag / displayName 等字段权重不在此列，保留在各自引擎里。
     */
    public static final double WEIGHT_NAME = 40;
    public static final double WEIGHT_BUSINESS_NAME = 35;
    public static final double WEIGHT_COMMENT = 30;
    public static final double WEIGHT_SEMANTIC_TYPE = 25;

    /**
     * 两个引擎共用的权重系数：人工 boost（null = 1.0）乘系统表降权。
     * 只作用于排序得分，不改任何 JSON 字段。
     */
    public static double effectiveBoost(Double boost, boolean system) {
        double value = boost == null ? 1.0 : boost;
        return system ? value * SYSTEM_TABLE_FACTOR : value;
    }

    /**
     * 查询是否恰好命中一条负向词——命中就该文档（通常是术语）整个不参与这次匹配。
     *
     * <p>只做整串相等（忽略首尾空白），不做子串/分词：负向词挡的是「这个查询词组恰好
     * 撞进了另一个域」，而不是「查询里出现了这个字」——后者会误伤太多正常查询。
     */
    public static boolean blockedByNegativeAlias(List<String> negativeAliases, String query) {
        if (negativeAliases == null || negativeAliases.isEmpty() || query == null) {
            return false;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String negative : negativeAliases) {
            if (negative != null && normalized.equals(negative.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查询切词：按非字母数字切块，CJK 块再切 bigram（单字块保留原字）。结果去重且保序。
     */
    public static List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder run = new StringBuilder();
        boolean runIsCjk = false;
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            boolean cjk = isCjk(c);
            boolean word = cjk || Character.isLetterOrDigit(c) || c == '_';
            if (word && run.length() > 0 && cjk != runIsCjk) {
                flush(tokens, run, runIsCjk);
            }
            if (word) {
                runIsCjk = cjk;
                run.append(c);
            } else {
                flush(tokens, run, runIsCjk);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(tokens));
    }

    private static void flush(List<String> tokens, StringBuilder run, boolean cjk) {
        if (run.length() == 0) {
            return;
        }
        String text = run.toString();
        run.setLength(0);
        if (!cjk || text.length() < 2) {
            tokens.add(text);
            return;
        }
        for (int i = 0; i + 2 <= text.length(); i++) {
            tokens.add(text.substring(i, i + 2));
        }
    }

    private static boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 单个字段值对单个词条的原始得分，0 表示不命中。 */
    static double termScore(String lowerValue, String term) {
        if (term.isEmpty()) {
            return 0;
        }
        if (lowerValue.equals(term)) {
            return 100;
        }
        if (lowerValue.endsWith("." + term)) {
            return 80;
        }
        if (lowerValue.contains(term)) {
            return Math.max(5, 40 - lowerValue.length() * 0.1);
        }
        return 0;
    }

    /** 一次命中：得分、命中字段名、命中的字段原文。 */
    private record Hit(double score, String field, String text) {
    }

    /**
     * 单个文档（表 / 列 / 术语）的打分器：依次喂字段，最后取整串命中与多词条命中的较高者。
     * 字段按重要性依次喂入，同分时先喂的字段胜出。
     */
    public static final class Scorer {
        private final String query;
        private final List<String> tokens;
        private final Map<String, Hit> tokenHits = new LinkedHashMap<>();
        private Hit queryHit;

        public Scorer(String query) {
            this.query = query == null ? "" : query.toLowerCase(Locale.ROOT);
            this.tokens = tokenize(query);
        }

        /**
         * @param field  字段名，作为 matchedField 输出
         * @param value  字段原文，null / 空白跳过
         * @param weight 字段基础权重
         */
        public void field(String field, String value, double weight) {
            if (value == null || value.isBlank()) {
                return;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            double whole = termScore(lower, query);
            if (whole > 0 && (queryHit == null || weight + whole > queryHit.score())) {
                queryHit = new Hit(weight + whole, field, value);
            }
            if (tokens.size() < 2) {
                return;
            }
            for (String token : tokens) {
                double score = termScore(lower, token);
                if (score <= 0) {
                    continue;
                }
                Hit previous = tokenHits.get(token);
                if (previous == null || weight + score > previous.score()) {
                    tokenHits.put(token, new Hit(weight + score, field, value));
                }
            }
        }

        public boolean matched() {
            return score() > 0;
        }

        public double score() {
            double whole = queryHit == null ? 0 : queryHit.score();
            return Math.max(whole, multiTokenScore());
        }

        public String matchedField() {
            Hit best = bestHit();
            return best == null ? null : best.field();
        }

        public String matchedText() {
            Hit best = bestHit();
            return best == null ? null : best.text();
        }

        /**
         * 多词条得分：命中词条的平均分（未命中词条按 0 计）再乘覆盖率。
         * 全部词条命中时量级与单字段整串命中相当，部分命中则明显低一档。
         */
        private double multiTokenScore() {
            if (tokenHits.isEmpty()) {
                return 0;
            }
            double sum = 0;
            for (Hit hit : tokenHits.values()) {
                sum += hit.score();
            }
            double coverage = (double) tokenHits.size() / tokens.size();
            return sum / tokens.size() * coverage;
        }

        private Hit bestHit() {
            if (queryHit != null && queryHit.score() >= multiTokenScore()) {
                return queryHit;
            }
            Hit best = null;
            for (Hit hit : tokenHits.values()) {
                if (best == null || hit.score() > best.score()) {
                    best = hit;
                }
            }
            return best;
        }
    }
}
