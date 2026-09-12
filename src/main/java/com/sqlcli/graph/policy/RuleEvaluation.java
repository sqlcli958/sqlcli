package com.sqlcli.graph.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sqlcli.graph.workspace.GraphActor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RuleEvaluation {
    private String id;
    private String sourceAlias;
    private long workspaceRevision;
    private String reviewType;
    private String inputType;
    private String inputRef;
    private String inputHash;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> inputRefs = new LinkedHashMap<>();
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> inputHashes = new LinkedHashMap<>();
    private String ruleSetId;
    private String ruleSetVersion;
    private String ruleSetHash;
    private String dbType;
    private String productVersion;
    private PolicyEvaluationStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private GraphActor actor;
    private int evaluatedRules;
    private int violationCount;
    private int waivedCount;
    private String errorMessage;
}
