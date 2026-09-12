package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段值域。
 *
 * <p>三个集合字段初始化成空集合而不是 null：调用方拿到 {@code new ColumnValueHints()}
 * 之后直接 {@code getSampleValues().add(...)} 是最自然的写法，留 null 会 NPE
 * （{@code schema edit --example} 就因此从来没能用过）。
 *
 * <p>配合 {@code NON_EMPTY}：空集合不落盘。九千多个字段每个多两个 {@code []}
 * 是白白撑大工作区文件。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ColumnValueHints {
    /**
     * 封闭值域，每项形如 {@code 0=待付款}；含义未知时只写值。
     *
     * <p>值多到不该内联的（字典表）不在这里——那是一条指向字典表的关系边，
     * join 表达式里带上 {@code dict_type} 之类的过滤条件。
     */
    private List<String> enumValues = new ArrayList<>();
    /** 值无限但有格式时的说明，例如「SM4 密文，前缀 ENC#240606#」。 */
    private String format;
    /** 开放值域的例子，由 {@code schema edit --example} 逐个追加。 */
    private List<String> sampleValues = new ArrayList<>();

    public boolean hasData() {
        return (enumValues != null && !enumValues.isEmpty())
                || format != null
                || (sampleValues != null && !sampleValues.isEmpty());
    }

    /**
     * 规范化并校验值域列表，每项形如 {@code 0=待付款}，含义未知时只写值。
     * CLI 的 {@code --enum-values} 与 UI 的字段编辑走同一份校验，语义不会分叉。
     *
     * <p>重复的值直接拒绝而不是后者覆盖前者：同一个值给出两个含义是写的人搞错了，
     * 静默取一个会让 Agent 之后一直照着错的那个生成 SQL。
     */
    public static List<String> normalizeEnumValues(List<String> items) {
        List<String> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String item : items) {
            int split = item.indexOf('=');
            String value = (split < 0 ? item : item.substring(0, split)).trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("值域取值不能为空: " + item);
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException("值域取值重复: " + value);
            }
            result.add(split < 0 ? value : value + "=" + item.substring(split + 1).trim());
        }
        return result;
    }
}
