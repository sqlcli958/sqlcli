package com.sqlcli.graph.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 任务级评估的**评分器**：给定评估集（期望）与 agent 的作答，判分并归因。
 *
 * <h2>这里不跑 agent</h2>
 * 跑 agent 是外部 harness 的事（Claude Code 起两个子 agent，把产出填进 answers.yaml）。
 * 本类只做纯函数：cases × answers → 报告。所以它没有任何 LLM 依赖，可以进 CI。
 *
 * <h2>为什么必须两组</h2>
 * 一次失败如果不分组，什么也说明不了——可能图谱缺信息，也可能 agent 笨，
 * 而两者的修法完全相反（补图谱 vs 改 skill）。所以每个 case 跑两组：
 * {@code control} 只给图谱、{@code cheat} 额外给正确表集合。见
 * docs/graph-concept-boundaries.zh-CN.md §6.2。
 *
 * <h2>pitfalls 为什么要可机判</h2>
 * 只比表集合和 JOIN 测不出「选错了名字相近的那张表」——那种 SQL 照样能跑、照样出数。
 * 所以每条 pitfall 必须带 {@code forbid}（出现即踩中）或 {@code require}（缺失即踩中），
 * 纯自然语言的 pitfall 直接在解析时报错：判不了的判据等于没有判据。
 */
public final class TaskEval {

    public static final String CONTROL = "control";
    public static final String CHEAT = "cheat";

    private TaskEval() {
    }

    /**
     * 一条坑：{@code desc} 是给人看的原话，{@code forbid}/{@code require} 是机判条件。
     *
     * @param forbid  任一串出现在作答里即判踩中（选错的那张表、写反的值域）
     * @param require 任一串在作答里找不到即判踩中（漏掉的必要过滤列）
     */
    public record Pitfall(String desc, List<String> forbid, List<String> require) {
    }

    public record TaskCase(String id, String task, List<String> tables, List<String> joins,
            List<Pitfall> pitfalls, String skillRef) {
    }

    /** 一组作答。{@code values} 是关键字段取值（字段 → 取值说明），参与 pitfall 匹配。 */
    public record Answer(List<String> tables, List<String> joins, String sql, Map<String, String> values) {
    }

    public enum Verdict {
        /** 对照组做对了 */
        passed,
        /** 对照组失败、作弊组通过 → 图谱信息不足，补描述/术语/检索 */
        graph,
        /** 两组都失败 → 给了表还做不对，改 skill 的指引 */
        skill,
        /** 缺组，或对照组过而作弊组反而失败 —— 说不出该修哪 */
        unattributable
    }

    public record GroupResult(boolean pass, List<String> missingTables, List<String> missingJoins,
            List<String> pitfalls) {
    }

    /** control/cheat 缺组时对应字段为 null。 */
    public record CaseResult(String id, String task, String skillRef, Verdict verdict,
            GroupResult control, GroupResult cheat, String note) {
    }

    public record Report(List<CaseResult> results) {

        public List<CaseResult> byVerdict(Verdict verdict) {
            return results.stream().filter(result -> result.verdict() == verdict).toList();
        }

        public double passRate() {
            return results.isEmpty() ? 0 : (double) byVerdict(Verdict.passed).size() / results.size();
        }

        /** 全部 case 通过 → 退出码 0；否则 1（CI 用）。 */
        public boolean allPassed() {
            return results.stream().allMatch(result -> result.verdict() == Verdict.passed);
        }
    }

    /**
     * @param answers caseId → (control|cheat) → 作答
     */
    public static Report evaluate(List<TaskCase> cases, Map<String, Map<String, Answer>> answers) {
        List<CaseResult> results = new ArrayList<>();
        for (TaskCase taskCase : cases) {
            Map<String, Answer> groups = answers.getOrDefault(taskCase.id(), Map.of());
            GroupResult control = score(taskCase, groups.get(CONTROL));
            GroupResult cheat = score(taskCase, groups.get(CHEAT));
            Verdict verdict;
            String note = null;
            if (control == null) {
                verdict = Verdict.unattributable;
                note = "对照组没有作答";
            } else if (control.pass()) {
                if (cheat != null && !cheat.pass()) {
                    verdict = Verdict.unattributable;
                    note = "对照组通过但作弊组失败——多给了正确表集合反而做错，重跑这一条";
                } else {
                    verdict = Verdict.passed;
                }
            } else if (cheat == null) {
                verdict = Verdict.unattributable;
                note = "作弊组缺失，分不清是图谱信息不足还是 skill 问题";
            } else {
                verdict = cheat.pass() ? Verdict.graph : Verdict.skill;
            }
            results.add(new CaseResult(taskCase.id(), taskCase.task(), taskCase.skillRef(),
                    verdict, control, cheat, note));
        }
        return new Report(results);
    }

    private static GroupResult score(TaskCase taskCase, Answer answer) {
        if (answer == null) {
            return null;
        }
        List<String> missingTables = taskCase.tables().stream()
                .filter(expected -> answer.tables().stream().noneMatch(actual -> sameTable(actual, expected)))
                .toList();
        List<String> missingJoins = taskCase.joins().stream()
                .filter(expected -> !joinSatisfied(answer, expected))
                .toList();
        String haystack = haystack(answer);
        List<String> hitPitfalls = taskCase.pitfalls().stream()
                .filter(pitfall -> hit(pitfall, haystack))
                .map(Pitfall::desc)
                .toList();
        boolean pass = missingTables.isEmpty() && missingJoins.isEmpty() && hitPitfalls.isEmpty();
        return new GroupResult(pass, missingTables, missingJoins, hitPitfalls);
    }

    /** 期望表集合是作答的子集即可（多带一张不算错）。按最后一段比，作答写不写 schema 前缀都认。 */
    private static boolean sameTable(String actual, String expected) {
        return lastSegment(actual).equals(lastSegment(expected));
    }

    private static String lastSegment(String ref) {
        String trimmed = ref == null ? "" : ref.trim().toLowerCase(Locale.ROOT);
        int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(dot + 1);
    }

    /**
     * 期望 JOIN 写成 {@code 左端 → 右端（说明）}。判据：作答的某一条 JOIN（或 SQL）里
     * 两端都出现、且左端在右端之前——顺带把方向判了。没写箭头的按整串包含判。
     */
    private static boolean joinSatisfied(Answer answer, String expected) {
        String[] endpoints = endpoints(expected);
        List<String> entries = new ArrayList<>(answer.joins());
        if (answer.sql() != null) {
            entries.add(answer.sql());
        }
        for (String entry : entries) {
            String text = normalize(entry);
            int left = text.indexOf(endpoints[0]);
            if (left < 0) {
                continue;
            }
            if (endpoints[1].isEmpty()) {
                return true;
            }
            int right = text.indexOf(endpoints[1], left + endpoints[0].length());
            if (right > left) {
                return true;
            }
        }
        return false;
    }

    /** 从 {@code a.x → b.y（注释）} 取出两端标识符；无箭头时右端为空串。 */
    private static String[] endpoints(String expected) {
        int arrow = expected.indexOf('→');
        int width = 1;
        if (arrow < 0) {
            arrow = expected.indexOf("->");
            width = 2;
        }
        if (arrow < 0) {
            return new String[] {normalize(expected), ""};
        }
        return new String[] {
                normalize(lastIdentifier(expected.substring(0, arrow))),
                normalize(firstIdentifier(expected.substring(arrow + width)))};
    }

    private static String lastIdentifier(String text) {
        List<String> all = identifiers(text);
        return all.isEmpty() ? text : all.get(all.size() - 1);
    }

    private static String firstIdentifier(String text) {
        List<String> all = identifiers(text);
        return all.isEmpty() ? text : all.get(0);
    }

    private static List<String> identifiers(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*").matcher(text);
        while (matcher.find()) {
            out.add(matcher.group());
        }
        return out;
    }

    private static boolean hit(Pitfall pitfall, String haystack) {
        for (String forbidden : pitfall.forbid()) {
            if (haystack.contains(normalize(forbidden))) {
                return true;
            }
        }
        for (String required : pitfall.require()) {
            if (!haystack.contains(normalize(required))) {
                return true;
            }
        }
        return false;
    }

    /** 作答的全部文本拼起来做 pitfall 匹配。用 {@code |} 分隔，避免去空白后跨字段粘出假命中。 */
    private static String haystack(Answer answer) {
        StringBuilder text = new StringBuilder();
        answer.tables().forEach(table -> text.append(table).append('|'));
        answer.joins().forEach(join -> text.append(join).append('|'));
        if (answer.sql() != null) {
            text.append(answer.sql()).append('|');
        }
        answer.values().forEach((key, value) -> text.append(key).append('=').append(value).append('|'));
        return normalize(text.toString());
    }

    /** 去掉全部空白并转小写：{@code is_scan = 0} 与 {@code is_scan=0} 判成同一件事。 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 解析

    public static List<TaskCase> parseCases(Path file) throws IOException {
        JsonNode root = yaml().readTree(file.toFile());
        JsonNode tasks = root.path("tasks");
        if (!tasks.isArray()) {
            throw new IllegalArgumentException("评估集文件需要顶层 tasks 数组: " + file);
        }
        List<TaskCase> cases = new ArrayList<>();
        for (JsonNode node : tasks) {
            String id = text(node, "id");
            if (id == null) {
                throw new IllegalArgumentException("每个 task 需要 id: " + node);
            }
            JsonNode expect = node.path("expect");
            List<Pitfall> pitfalls = new ArrayList<>();
            for (JsonNode pitfallNode : expect.path("pitfalls")) {
                pitfalls.add(parsePitfall(id, pitfallNode));
            }
            cases.add(new TaskCase(id, text(node, "task"),
                    strings(expect.path("tables")), strings(expect.path("joins")),
                    pitfalls, text(node, "skillRef")));
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评估集文件没有可用的 tasks: " + file);
        }
        return cases;
    }

    private static Pitfall parsePitfall(String caseId, JsonNode node) {
        List<String> forbid = strings(node.path("forbid"));
        List<String> require = strings(node.path("require"));
        if (forbid.isEmpty() && require.isEmpty()) {
            throw new IllegalArgumentException("task " + caseId
                    + " 的 pitfall 缺少 forbid/require，判不了: " + node.toString());
        }
        String desc = text(node, "desc");
        return new Pitfall(desc == null ? node.toString() : desc, forbid, require);
    }

    public static Map<String, Map<String, Answer>> parseAnswers(Path file) throws IOException {
        JsonNode root = yaml().readTree(file.toFile());
        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            throw new IllegalArgumentException("作答文件需要顶层 answers 映射: " + file);
        }
        Map<String, Map<String, Answer>> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> cases = answers.fields();
        while (cases.hasNext()) {
            Map.Entry<String, JsonNode> caseEntry = cases.next();
            Map<String, Answer> groups = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> groupNodes = caseEntry.getValue().fields();
            while (groupNodes.hasNext()) {
                Map.Entry<String, JsonNode> group = groupNodes.next();
                if (!CONTROL.equals(group.getKey()) && !CHEAT.equals(group.getKey())) {
                    throw new IllegalArgumentException(caseEntry.getKey() + " 下只能有 control / cheat 两组，收到: "
                            + group.getKey());
                }
                groups.put(group.getKey(), parseAnswer(group.getValue()));
            }
            out.put(caseEntry.getKey(), groups);
        }
        return out;
    }

    private static Answer parseAnswer(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.path("values").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), field.getValue().asText());
        }
        return new Answer(strings(node.path("tables")), strings(node.path("joins")),
                text(node, "sql"), values);
    }

    private static ObjectMapper yaml() {
        return new ObjectMapper(new YAMLFactory());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }
}
