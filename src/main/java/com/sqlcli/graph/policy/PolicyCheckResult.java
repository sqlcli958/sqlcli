package com.sqlcli.graph.policy;

import java.util.List;

/**
 * @param skippedRules 因作用域收窄没跑的规则数（见 {@link PolicyScope}）。
 *                     不报出来的话「0 条违规」和「这条规则根本没跑」在输出里长得一模一样。
 */
public record PolicyCheckResult(RuleEvaluation evaluation, List<PolicyViolation> violations, int exitCode,
        int skippedRules) {
}
