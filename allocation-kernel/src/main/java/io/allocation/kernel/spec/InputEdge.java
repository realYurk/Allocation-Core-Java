package io.allocation.kernel.spec;

/**
 * An incoming edge into a stage.
 *
 * @param share proportion of each upstream row routed here, scaled by 10^6; {@code null} means 1
 */
public record InputEdge(String from, String share) {

    public static InputEdge of(String from) {
        return new InputEdge(from, null);
    }

    public String effectiveShare() {
        return share == null || share.isEmpty() ? "1" : share;
    }
}
