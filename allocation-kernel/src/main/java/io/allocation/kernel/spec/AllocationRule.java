package io.allocation.kernel.spec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One allocation rule inside a stage: which cohort of the pool is split, using which driver weights.
 *
 * <p>All active rules in a stage share the pool by {@code share}, and the shares must sum to exactly
 * 1 (10^6). {@code signedDriverReason} is mandatory for {@link NegativeDriverPolicy#ALLOW_SIGNED}:
 * unsigned-weighted allocation is the normal case, and permitting negative weights is a business
 * exception that must be justified in the rule file itself.
 *
 * @param id                 stable identifier, used in evidence and row identifiers
 * @param enabled            {@code null} means enabled
 * @param share              proportion of the pool handled by this rule, scaled by 10^6
 * @param driverDataset      key into {@code datasets.drivers}
 * @param weightField        column holding the weight
 * @param driverFilters      optional row filters applied to the driver dataset
 * @param negativePolicy     {@code null} behaves as {@link NegativeDriverPolicy#REJECT}
 * @param signedDriverReason human justification, 8..256 characters, single line
 */
public record AllocationRule(
        String id,
        Boolean enabled,
        String share,
        String driverDataset,
        String weightField,
        Map<String, FilterSpec> driverFilters,
        NegativeDriverPolicy negativePolicy,
        String signedDriverReason) {

    public AllocationRule {
        Map<String, FilterSpec> copy = new LinkedHashMap<>();
        if (driverFilters != null) {
            copy.putAll(driverFilters);
        }
        driverFilters = Map.copyOf(copy);
    }

    public static AllocationRule simple(String id, String dataset, String weightField) {
        return new AllocationRule(id, null, "1", dataset, weightField, Map.of(),
                NegativeDriverPolicy.REJECT, null);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String effectiveShare() {
        return share == null || share.isEmpty() ? "1" : share;
    }

    public NegativeDriverPolicy policy() {
        return negativePolicy == null ? NegativeDriverPolicy.REJECT : negativePolicy;
    }

    public boolean allowsSigned() {
        return policy() == NegativeDriverPolicy.ALLOW_SIGNED;
    }
}
