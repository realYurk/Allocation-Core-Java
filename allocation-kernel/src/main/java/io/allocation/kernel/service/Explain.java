package io.allocation.kernel.service;

import java.util.List;
import java.util.Map;

/**
 * The complete decision chain for one coordinate.
 *
 * <p>"Explain" is not a summary. It answers the question a controller actually asks — why did this
 * partner receive 370.37 and not 370.38 — with the pool it came from, the weight, the denominator, its
 * position in the settlement order, and the rule digest that was in force.
 *
 * @param ruleSha256 digest of the rule document the run executed
 */
public record Explain(String runId, Map<String, String> coord, String ruleSha256, List<Step> steps) {

    public Explain {
        coord = Map.copyOf(coord);
        steps = List.copyOf(steps);
    }

    /**
     * One pool's contribution to the coordinate.
     *
     * @param remainderRank position in the settlement order, {@code 0} first in line for a residual unit
     * @param gotResidual   whether this amount includes a residual unit
     */
    public record Step(String stageId, String ruleId, String poolKey, Money poolAmount, Money shareAmount,
                       long weight, long denominator, int remainderRank, boolean gotResidual,
                       Money allocatedAmount) {
    }
}
