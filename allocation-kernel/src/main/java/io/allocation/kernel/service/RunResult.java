package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;

import java.util.List;
import java.util.Map;

/**
 * One page of a published run's results.
 *
 * @param conservation whole-run balance, so a caller sees the total even when paging lines
 */
public record RunResult(String runId, RunRepository.Status status, List<Preview.AllocatedLine> lines,
                        Preview.Conservation conservation, int page, int size, int total) {

    public RunResult {
        lines = List.copyOf(lines);
    }
}
