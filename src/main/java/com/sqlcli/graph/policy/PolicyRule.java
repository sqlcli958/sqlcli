package com.sqlcli.graph.policy;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class PolicyRule {
    private String id;
    private String title;
    private String category;
    private Map<String, Object> when = new LinkedHashMap<>();
    private Map<String, Object> statement = new LinkedHashMap<>();
    private PolicySeverity severity;
    private PolicyEnforcement enforcement;
    /** 见 {@link PolicyScope}；由 {@code RuleSetLoader} 按类别兜底，加载后不为 null。 */
    private PolicyScope scope;
    private String remediation;
    private List<String> tags = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
}
