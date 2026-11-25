
package jetbrains.buildServer.clouds.kubernetes;

/**
 * Thrown when a configured instance limit ({@code profileInstanceLimit} or
 * {@code imageInstanceLimit}) cannot be parsed as an integer.
 *
 * <p>It is an unchecked {@link IllegalArgumentException} carrying a user-readable message.
 * Runtime quota checks catch it and turn it into a negative
 * {@link jetbrains.buildServer.clouds.CanStartNewInstanceResult} instead of letting a
 * {@link NumberFormatException} break profile operations.
 */
public class InvalidInstanceLimitException extends IllegalArgumentException {
    public InvalidInstanceLimitException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
