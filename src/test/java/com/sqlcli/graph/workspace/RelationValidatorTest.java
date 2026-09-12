package com.sqlcli.graph.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationValidatorTest {
    private final RelationValidator validator = new RelationValidator();

    @Test
    void rejectsConfidenceOutsideZeroToOneRange() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.join_observed,
                GraphObjectKind.column,
                GraphObjectKind.column,
                1.1);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("between 0.0 and 1.0"));
    }

    @Test
    void acceptsDocumentedConfidenceForValidEndpoints() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.join_observed,
                GraphObjectKind.column,
                GraphObjectKind.column,
                0.9);

        assertTrue(result.isValid());
    }

    @Test
    void rejectsInferredRelationWithoutExplicitConfidence() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.join_observed,
                GraphObjectKind.column,
                GraphObjectKind.column,
                null);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("requires an explicit confidence"));
    }

    @Test
    void allowsDeclaredRelationWithoutConfidence() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.term_mapping,
                GraphObjectKind.term,
                GraphObjectKind.table,
                null);

        assertTrue(result.isValid());
    }

    @Test
    void rejectsVerifiedBelowNinetyPercentConfidence() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.join_observed,
                GraphObjectKind.column,
                GraphObjectKind.column,
                0.85,
                true);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("verified=true requires confidence")));
    }

    @Test
    void allowsVerifiedAtNinetyPercentConfidence() {
        RelationValidator.ValidationResult result = validator.validate(
                RelationType.join_observed,
                GraphObjectKind.column,
                GraphObjectKind.column,
                0.9,
                true);

        assertTrue(result.isValid());
    }

    @Test
    void inferredRelationHasNoDefaultConfidenceButDeclaredDoes() {
        RelationWorkspaceEdge inferred = RelationWorkspaceEdge.create(
                "unit", RelationType.join_observed, "a", "b", GraphActor.agent);
        assertNull(inferred.getConfidence());
        assertEquals(GraphStatus.candidate, inferred.getStatus());

        RelationWorkspaceEdge declared = RelationWorkspaceEdge.create(
                "unit", RelationType.foreign_key, "a", "b", GraphActor.human);
        assertEquals(1.0, declared.getConfidence(), 1e-9);
        assertEquals(GraphStatus.partial, declared.getStatus());
    }
}
