package com.sqlcli.graph.policy;

import java.util.List;

public record PolicyRun(RuleEvaluation evaluation, RuleSet ruleSet, List<PolicyViolation> violations) {
}
