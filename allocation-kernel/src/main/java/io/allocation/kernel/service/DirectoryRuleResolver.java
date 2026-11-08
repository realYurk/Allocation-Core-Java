package io.allocation.kernel.service;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;
import io.allocation.kernel.spec.SpecCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads rules from files named {@code <code>.json} in one directory.
 *
 * <p>The digest is computed from the bytes that were read, not from a re-serialisation of the parsed
 * document. That distinction is the whole point: a run has to be able to prove which bytes it executed,
 * and a re-serialisation would silently normalise whitespace and field order.
 */
public final class DirectoryRuleResolver implements RuleResolver {

    private final Path directory;

    public DirectoryRuleResolver(Path directory) {
        this.directory = directory.toAbsolutePath();
    }

    @Override
    public Optional<RuleDocument> find(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank() || ruleCode.contains("..")
                || ruleCode.contains("/") || ruleCode.contains("\\")) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "invalid rule code " + ruleCode);
        }
        Path path = directory.resolve(ruleCode + ".json");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return Optional.of(new RuleDocument(ruleCode, new String(bytes, StandardCharsets.UTF_8),
                    SpecCodec.sha256Hex(bytes), directory));
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.RULE_INVALID, "cannot read rule " + path, e);
        }
    }
}
