
package jetbrains.buildServer.clouds.kubernetes;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parsing and validation rules for the configured instance limits
 * ({@code profileInstanceLimit} and {@code imageInstanceLimit}).
 *
 * <p>Boundary rules:
 * <ul>
 *   <li>{@code null} or an empty string means "not configured" &rarr; any negative value
 *       ({@link #UNLIMITED}) which the quota checks treat as unlimited.</li>
 *   <li>any negative integer means unlimited;</li>
 *   <li>{@code 0} forbids starting new instances;</li>
 *   <li>a positive integer is the maximum number of occupied slots.</li>
 * </ul>
 *
 * <p>Values that do not parse as an integer (for example {@code "3a"} or {@code " 3"}, because
 * {@link Integer#parseInt(String)} is whitespace-strict) are rejected with a readable
 * {@link InvalidInstanceLimitException} instead of a raw {@link NumberFormatException}.
 */
public final class InstanceLimits {

    /** Value returned for an unset/empty limit; all negative values share the "unlimited" meaning. */
    public static final int UNLIMITED = -1;

    private InstanceLimits() {
    }

    /**
     * Parses a raw limit parameter.
     *
     * @param raw         raw property value, may be {@code null}
     * @param description human-readable name of the setting used in the error message
     * @return the parsed integer (negative values kept as-is for compatibility),
     *         or {@link #UNLIMITED} when the value is {@code null}/empty
     * @throws InvalidInstanceLimitException if the value is non-empty but not an integer
     */
    public static int parse(@Nullable final String raw, @NotNull final String description) {
        if (raw == null || raw.isEmpty()) {
            return UNLIMITED;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new InvalidInstanceLimitException(
                    description + " must be an integer, but was '" + raw + "'. "
                            + "Leave the field empty or use a negative value for unlimited, "
                            + "use 0 to forbid starting new instances, or use a positive integer as the limit.",
                    e);
        }
    }

    /**
     * Validates a raw limit parameter for save-time properties processing.
     *
     * @return {@code null} when valid, otherwise a readable validation message
     */
    @Nullable
    public static String validate(@Nullable final String raw, @NotNull final String description) {
        try {
            parse(raw, description);
            return null;
        } catch (InvalidInstanceLimitException e) {
            return e.getMessage();
        }
    }

    /**
     * @return {@code true} if the parsed limit means unlimited ({@code null}/empty or negative)
     */
    public static boolean isUnlimited(final int limit) {
        return limit < 0;
    }
}
