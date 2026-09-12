package com.sqlcli.graph.policy;

import com.sqlcli.graph.workspace.GraphWorkspace;
import com.sqlcli.graph.workspace.GraphWorkspaceStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRuleSetManagerTest {
    @Test
    void createsUpdatesBindsAndDeletesRuleSet() throws Exception {
        Path root = Files.createTempDirectory("policy-rule-manager-");
        try {
            GraphWorkspaceStore store = new GraphWorkspaceStore(root);
            store.save(GraphWorkspace.create("unit", "mysql"));
            PolicyRuleSetManager manager = new PolicyRuleSetManager(store);

            manager.create("unit", "rules.yaml", ruleSet("Initial"));
            assertFalse(manager.list("unit").get(0).enabled());
            manager.setBound("unit", "rules.yaml", true);
            assertTrue(manager.list("unit").get(0).enabled());
            manager.update("unit", "rules.yaml", ruleSet("Updated"));
            assertEquals("Updated", manager.list("unit").get(0).ruleSet().getTitle());
            manager.delete("unit", "rules.yaml");
            assertTrue(manager.list("unit").isEmpty());
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private RuleSet ruleSet(String title) {
        PolicyRule rule = new PolicyRule();
        rule.setId("primary-key");
        rule.setTitle("Primary key required");
        rule.setCategory("primary_key_required");
        rule.setSeverity(PolicySeverity.error);
        rule.setEnforcement(PolicyEnforcement.required);
        RuleSet ruleSet = new RuleSet();
        ruleSet.setKind("PolicyRuleSet");
        ruleSet.setId("unit-rules");
        ruleSet.setTitle(title);
        ruleSet.setVersion("1");
        ruleSet.getRules().add(rule);
        return ruleSet;
    }
}
