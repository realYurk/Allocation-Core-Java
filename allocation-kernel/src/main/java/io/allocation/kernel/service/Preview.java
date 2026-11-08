package io.allocation.kernel.service;

import io.allocation.kernel.exec.RunSink;

import java.util.List;
import java.util.Map;

/**
 * The answer to "what would this rule do with these rows", with enough evidence to defend it.
 *
 * @param lines        one entry per terminal coordinates
 * @param conservation input against output for the whole preview
 * @param warnings     non-fatal observations carried through from the engine
 */
public record Preview(List<AllocatedLine> lines, Conservation conservation, List<RunSink.Warning> warnings) {

    public Preview {
        lines = List.copyOf(lines);
        warnings = List.copyOf(warnings);
    }

    /** One allocated coordinate and the decision that produced it. */
    public record AllocatedLine(Map<String, String> coord, Money amount, ShareTrace trace) {

        public AllocatedLine {
            coord = Map.copyOf(coord);
        }
    }

    /**
     * Why this coordinate received this amount.
     *
     * <p>{@code weight} and {@code denominator} are driver units, scaled by 10<sup>6</sup>, the same
     * scale the rule uses for shares. They are reported raw rather than as a percentage so the numbers
     * can be checked against the source file without rounding, which is the point of an explanation.
     *
     * @param poolAmount    the pool the coordinate was allocated from
     * @param weight        driver weight for this target, scaled by 10<sup>6</sup>
     * @param denominator   sum of driver weights in the pool, scaled by 10<sup>6</sup>
     * @param remainderRank position in the settlement order, {@code 0} first in line
     * @param gotResidual   whether the amount includes a residual unit
     */
    public record ShareTrace(Money poolAmount, long weight, long denominator, int remainderRank,
                             boolean gotResidual) {
    }

    /** Whole-preview balance check. */
    public record Conservation(Money input, Money output, boolean balanced) {
    }
}
