package io.allocation.kernel.exec;

import io.allocation.kernel.coord.Coord;

/**
 * One driver (weight) row.
 *
 * <p>{@code coord} may omit dimensions that the driver file does not carry; an omitted dimension is
 * distinct from one that is present but empty. The distinction decides whether a {@code SOURCE} target
 * discards the row, so it is modelled explicitly rather than collapsed to an empty string.
 *
 * @param weight the value of the configured weight column, scaled by 10^6
 */
public record DriverRow(Coord coord, long weight) {
}
