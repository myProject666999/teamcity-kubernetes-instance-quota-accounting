
package jetbrains.buildServer.clouds.kubernetes;

import java.util.LinkedHashMap;
import java.util.Map;
import jetbrains.buildServer.clouds.CloudInstance;
import jetbrains.buildServer.clouds.InstanceStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable quota occupancy counters built from <b>one</b> read pass over the instances.
 *
 * <p>An instance occupies profile/image quota while the corresponding Kubernetes resource may
 * still exist or while TeamCity still needs the slot to serialize start decisions. In contrast to
 * {@link InstanceStatus#isStartingOrStarted()} (which only covers the starting/running family),
 * quota occupancy also includes the terminating states: the pod is still present in the cluster
 * until deletion completes, so excluding it would let queued builds create replacement pods before
 * the old ones are gone and temporarily exceed the cluster capacity.
 *
 * <p>The counted status groups are:
 * <ul>
 *   <li>{@link Group#RUNNING}: {@link InstanceStatus#RUNNING}</li>
 *   <li>{@link Group#STARTING}: {@link InstanceStatus#SCHEDULED_TO_START},
 *       {@link InstanceStatus#STARTING}, {@link InstanceStatus#RESTARTING}</li>
 *   <li>{@link Group#TERMINATING}: {@link InstanceStatus#SCHEDULED_TO_STOP},
 *       {@link InstanceStatus#STOPPING}</li>
 * </ul>
 *
 * <p>Not counted: {@link InstanceStatus#UNKNOWN}, {@link InstanceStatus#STOPPED},
 * {@link InstanceStatus#ERROR} and {@link InstanceStatus#ERROR_CANNOT_STOP}.
 *
 * <p>Use {@link #of(Iterable)} once and derive every comparison and user-visible number from the
 * same instance so that the rejection message can never disagree with the check.
 */
public final class InstanceQuotaSnapshot {

    /**
     * Quota group of an {@link InstanceStatus}.
     */
    public enum Group {
        /** {@link InstanceStatus#RUNNING}. */
        RUNNING,
        /** SCHEDULED_TO_START / STARTING / RESTARTING. */
        STARTING,
        /** SCHEDULED_TO_STOP / STOPPING. */
        TERMINATING,
        /** Status does not currently occupy a quota slot. */
        NOT_COUNTED
    }

    public static final InstanceQuotaSnapshot EMPTY = new InstanceQuotaSnapshot(0, 0, 0);

    private final int myRunning;
    private final int myStarting;
    private final int myTerminating;

    public InstanceQuotaSnapshot(final int running, final int starting, final int terminating) {
        myRunning = running;
        myStarting = starting;
        myTerminating = terminating;
    }

    /**
     * Classifies a TeamCity instance status for quota accounting.
     *
     * @param status the status, {@code null} is treated as {@link Group#NOT_COUNTED}
     * @return the quota group
     */
    @NotNull
    public static Group groupOf(@Nullable final InstanceStatus status) {
        if (status == null) {
            return Group.NOT_COUNTED;
        }
        if (status == InstanceStatus.RUNNING) {
            return Group.RUNNING;
        }
        // Reuse the platform definition for the starting/started family. Verified against
        // cloud-api (2022.10 and 2026.3): it returns true exactly for SCHEDULED_TO_START,
        // STARTING, RESTARTING and RUNNING.
        if (status.isStartingOrStarted()) {
            return Group.STARTING;
        }
        if (status == InstanceStatus.SCHEDULED_TO_STOP || status == InstanceStatus.STOPPING) {
            return Group.TERMINATING;
        }
        return Group.NOT_COUNTED;
    }

    /**
     * @return {@code true} if an instance with this status occupies a quota slot
     */
    public static boolean occupiesQuota(@Nullable final InstanceStatus status) {
        return groupOf(status) != Group.NOT_COUNTED;
    }

    /**
     * Builds a snapshot by iterating over the given instances exactly once.
     * A {@code null} or empty collection yields {@link #EMPTY}.
     */
    @NotNull
    public static InstanceQuotaSnapshot of(@Nullable final Iterable<? extends CloudInstance> instances) {
        if (instances == null) {
            return EMPTY;
        }
        int running = 0;
        int starting = 0;
        int terminating = 0;
        for (final CloudInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            switch (groupOf(instance.getStatus())) {
                case RUNNING:
                    running++;
                    break;
                case STARTING:
                    starting++;
                    break;
                case TERMINATING:
                    terminating++;
                    break;
                default:
                    break;
            }
        }
        return new InstanceQuotaSnapshot(running, starting, terminating);
    }

    /**
     * Builds the global profile snapshot and one snapshot per image from a single pass over
     * every image's instance collection. Each image's instances are iterated once.
     */
    @NotNull
    public static Map<String, InstanceQuotaSnapshot> byImage(@NotNull final Iterable<? extends KubeCloudImage> images) {
        final Map<String, InstanceQuotaSnapshot> result = new LinkedHashMap<>();
        for (final KubeCloudImage image : images) {
            if (image == null || image.getId() == null) {
                continue;
            }
            result.put(image.getId(), of(image.getInstances()));
        }
        return result;
    }

    @NotNull
    public InstanceQuotaSnapshot plus(@NotNull final InstanceQuotaSnapshot other) {
        return new InstanceQuotaSnapshot(
                myRunning + other.myRunning,
                myStarting + other.myStarting,
                myTerminating + other.myTerminating);
    }

    /** Running instances ({@code RUNNING}). */
    public int getRunning() {
        return myRunning;
    }

    /** Starting instances ({@code SCHEDULED_TO_START}, {@code STARTING}, {@code RESTARTING}). */
    public int getStarting() {
        return myStarting;
    }

    /** Terminating instances ({@code SCHEDULED_TO_STOP}, {@code STOPPING}). */
    public int getTerminating() {
        return myTerminating;
    }

    /**
     * @return total number of quota slots occupied: running + starting + terminating
     */
    public int getOccupied() {
        return myRunning + myStarting + myTerminating;
    }

    /**
     * Compact, language-neutral numeric suffix shared by the profile/image rejection messages so
     * that tests can extract the counts. Example: {@code occupied=5 running=3 starting=1 terminating=1}.
     */
    @NotNull
    public String toCountString() {
        return "occupied=" + getOccupied()
                + " running=" + myRunning
                + " starting=" + myStarting
                + " terminating=" + myTerminating;
    }

    @Override
    public String toString() {
        return "InstanceQuotaSnapshot{" + toCountString() + '}';
    }
}
