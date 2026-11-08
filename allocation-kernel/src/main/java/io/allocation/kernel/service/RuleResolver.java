package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Supplies rule documents by code.
 *
 * <p>A published rule is a control document: the run records its digest, so the resolver has to hand
 * back exactly the bytes it stored. It also reports the directory the rule's dataset paths are relative
 * to, because a rule that says {@code amounts.csv} means "next to me", not "relative to the process".
 */
public interface RuleResolver {

    /**
     * A resolved rule.
     *
     * @param json          the document text, byte for byte as published
     * @param sha256        digest of {@code json}
     * @param baseDirectory where relative dataset paths resolve
     */
    record RuleDocument(String code, String json, String sha256, Path baseDirectory) {
        public RuleDocument {
            if (baseDirectory == null) {
                baseDirectory = Path.of(".").toAbsolutePath();
            }
        }
    }

    Optional<RuleDocument> find(String ruleCode);

    /** Resolves a rule or fails with {@code ALLOC_001}, naming the code that was asked for. */
    default RuleDocument require(String ruleCode) {
        return find(ruleCode).orElseThrow(() -> new AllocationException(ErrorCode.RULE_INVALID,
                "unknown rule " + ruleCode));
    }
}
