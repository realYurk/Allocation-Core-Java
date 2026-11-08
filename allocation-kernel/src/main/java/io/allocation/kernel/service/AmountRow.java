package io.allocation.kernel.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One amount row, as a caller supplies it.
 *
 * @param coord  dimension name to member; names not in the rule are ignored
 * @param amount scaled amount
 */
public record AmountRow(Map<String, String> coord, Money amount) {

    public AmountRow {
        coord = Map.copyOf(coord);
    }
}
