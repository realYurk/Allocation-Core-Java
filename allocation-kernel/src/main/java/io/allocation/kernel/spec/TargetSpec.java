package io.allocation.kernel.spec;

import java.util.List;

/** Target derivation rule for one dimension. */
public record TargetSpec(TargetMode mode, List<String> include, List<String> exclude, String value) {

    public TargetSpec {
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    public static TargetSpec source() {
        return new TargetSpec(TargetMode.SOURCE, List.of(), List.of(), null);
    }

    public static TargetSpec element(String value) {
        return new TargetSpec(TargetMode.ELEMENT, List.of(), List.of(), value);
    }

    public static TargetSpec include(List<String> members) {
        return new TargetSpec(TargetMode.INCLUDE, members, List.of(), null);
    }

    /** True when this dimension takes the target member from the driver row. */
    public boolean isDriverDerived() {
        return mode == TargetMode.INCLUDE || mode == TargetMode.INCLUDE_EXCLUDE;
    }
}
