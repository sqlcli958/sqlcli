package com.sqlcli.graph.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 字段的业务语义类型。
 *
 * 固定枚举而不是自由文本：它的消费方是规则引擎（PolicyEvaluator 的 semanticTypeAny 条件，
 * 例如「所有 phone 字段必须声明脱敏策略」）和搜索加权，两者都要求取值可枚举、可匹配。
 * 自由文本会让同一个语义出现"手机号"/"phone"/"mobile"三种写法，规则一条都命中不了。
 *
 * 分三类：敏感信息（需要脱敏/审计）、业务含义、系统字段。
 */
public enum SemanticType {
    // ── 敏感信息 ──
    phone("手机号", Category.sensitive),
    id_card("身份证号", Category.sensitive),
    email("邮箱", Category.sensitive),
    person_name("姓名", Category.sensitive),
    address("地址", Category.sensitive),
    bank_card("银行卡号", Category.sensitive),
    account("账号", Category.sensitive),

    // ── 业务含义 ──
    amount("金额", Category.business),
    quantity("数量", Category.business),
    status("状态", Category.business),
    code("业务编码", Category.business),
    name("名称", Category.business),
    description("描述", Category.business),

    // ── 系统字段 ──
    primary_id("主键 ID", Category.system),
    ref_id("关联 ID", Category.system),
    created_at("创建时间", Category.system),
    updated_at("更新时间", Category.system),
    created_by("创建人", Category.system),
    updated_by("更新人", Category.system),
    deleted_flag("逻辑删除标记", Category.system),
    version("版本号", Category.system);

    public enum Category {
        sensitive, business, system
    }

    private final String label;
    private final Category category;

    SemanticType(String label, Category category) {
        this.label = label;
        this.category = category;
    }

    @JsonValue
    public String value() {
        return name();
    }

    public String getLabel() {
        return label;
    }

    public Category getCategory() {
        return category;
    }

    /** 敏感类字段，规则引擎默认关注这一组。 */
    public boolean isSensitive() {
        return category == Category.sensitive;
    }

    /**
     * 宽松解析。
     *
     * 旧图谱里 semanticType 是自由文本，可能存着任意字符串；解析不出来时返回 null
     * 而不是抛异常——为一个标注字段让整个 workspace 读不出来不值得。
     */
    @JsonCreator
    public static SemanticType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /** 给 UI 下拉用：value -> 中文标签。 */
    public static Map<String, String> optionLabels() {
        Map<String, String> options = new LinkedHashMap<>();
        for (SemanticType type : values()) {
            options.put(type.name(), type.label);
        }
        return options;
    }
}
