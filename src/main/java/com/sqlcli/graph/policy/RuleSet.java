package com.sqlcli.graph.policy;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class RuleSet {
    private String kind;
    private String id;
    private String title;
    private String version;
    private String dbType;
    private Map<String, Object> match = new LinkedHashMap<>();
    private Map<String, Object> defaults = new LinkedHashMap<>();
    private List<PolicyRule> rules = new ArrayList<>();
    private List<String> sources = new ArrayList<>();
}
