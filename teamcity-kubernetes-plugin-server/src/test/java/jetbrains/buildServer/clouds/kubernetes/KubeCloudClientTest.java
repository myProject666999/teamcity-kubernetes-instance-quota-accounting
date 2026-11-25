
package jetbrains.buildServer.clouds.kubernetes;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.clouds.CanStartNewInstanceResult;
import jetbrains.buildServer.clouds.CloudClientParameters;
import jetbrains.buildServer.clouds.CloudImage;
import jetbrains.buildServer.clouds.CloudImageParameters;
import jetbrains.buildServer.clouds.CloudInstanceUserData;
import jetbrains.buildServer.clouds.InstanceStatus;
import jetbrains.buildServer.clouds.kubernetes.connector.KubeApiConnector;
import jetbrains.buildServer.clouds.kubernetes.podSpec.BuildAgentPodTemplateProvider;
import jetbrains.buildServer.clouds.kubernetes.podSpec.BuildAgentPodTemplateProviders;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.*;

import static jetbrains.buildServer.clouds.kubernetes.KubeParametersConstants.PROFILE_INSTANCE_LIMIT;
import static org.assertj.core.api.BDDAssertions.then;

/**
 * Created by ekoshkin (koshkinev@gmail.com) on 29.06.17.
 */
@Test
public class KubeCloudClientTest extends BaseTestCase {
    private Mockery m;
    private KubeApiConnector myApi;
    private BuildAgentPodTemplateProviders myPodTemplateProviders;
    private Map<String, BuildAgentPodTemplateProvider> myPodTemplateProvidersMap;
    private ExecutorService myExecutorService;

    private KubeBackgroundUpdater myUpdater = new KubeBackgroundUpdater() {
        @Override
        public void registerClient(@NotNull KubeCloudClient client) {

        }

        @Override
        public void unregisterClient(@NotNull KubeCloudClient client) {

        }
    };

    @BeforeMethod
    public void setUp() throws Exception {
        super.setUp();
        m = new Mockery();
        myApi = m.mock(KubeApiConnector.class);
        myExecutorService = new InProcessExecutor();
        myPodTemplateProvidersMap = new HashMap<>();
        myPodTemplateProviders = new BuildAgentPodTemplateProviders() {
            @NotNull
            @Override
            public Collection<BuildAgentPodTemplateProvider> getAll() {
                return myPodTemplateProviders.getAll();
            }

            @Nullable
            @Override
            public BuildAgentPodTemplateProvider find(@Nullable String id) {
                return myPodTemplateProvidersMap.get(id);
            }
        };
    }

    @AfterMethod
    public void tearDown() throws Exception {
        m.assertIsSatisfied();
        super.tearDown();
    }

    public void testIsInitialized() throws Exception {
        assertTrue(createClient(Collections.emptyList()).isInitialized());
    }

    public void testCanStartNewInstance_UnknownImage() throws Exception {
        KubeCloudClient cloudClient = createClient(Collections.emptyList());
        CloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getId(); will(returnValue("image-1-id"));
        }});
        assertFalse(cloudClient.canStartNewInstance(image));
    }

    public void testCanStartNewInstance() throws Exception {
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getName(); will(returnValue("image-1-name"));
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getRunningInstanceCount(); will(returnValue(0));
            allowing(image).getInstances(); will(returnValue(Collections.emptyList()));
            allowing(image).getInstanceLimit(); will(returnValue(0));
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        KubeCloudClient cloudClient = createClient(images);
        assertFalse(cloudClient.canStartNewInstance(image));
    }

    @Test
    public void testCanStartNewInstance2() throws Exception {
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getName(); will(returnValue("image-1-name"));
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getRunningInstanceCount(); will(returnValue(1));
            allowing(image).getInstances(); will(returnValue(Collections.singletonList(statusInstance(InstanceStatus.RUNNING))));
            allowing(image).getInstanceLimit(); will(returnValue(5));
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        KubeCloudClient cloudClient = createClient(images);
        assertTrue(cloudClient.canStartNewInstanceWithDetails(image).isPositive());
    }

    public void testCanStartNewInstance_ProfileLimit() throws Exception {
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getRunningInstanceCount(); will(returnValue(1));
            allowing(image).getInstances(); will(returnValue(Collections.singletonList(statusInstance(InstanceStatus.RUNNING))));
            allowing(image).getInstanceLimit(); will(returnValue(2));
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        CloudClientParameters cloudClientParameters = new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "1"));
        KubeCloudClient cloudClient = createClient(images, cloudClientParameters);
        assertFalse(cloudClient.canStartNewInstance(image));
    }

    public void testCanStartNewInstance_ProfileLimit2() throws Exception {
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getRunningInstanceCount(); will(returnValue(3));
            allowing(image).getInstances(); will(returnValue(Arrays.asList(
              statusInstance(InstanceStatus.RUNNING),
              statusInstance(InstanceStatus.RUNNING),
              statusInstance(InstanceStatus.RUNNING))));
            allowing(image).getInstanceLimit(); will(returnValue(5));
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        CloudClientParameters cloudClientParameters = new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "5"));
        KubeCloudClient cloudClient = createClient(images, cloudClientParameters);
        CanStartNewInstanceResult result = cloudClient.canStartNewInstanceWithDetails(image);
        then(result.isPositive()).as(result.getReason()).isTrue();
    }

    public void testCanStartNewInstance_ImageLimit() throws Exception {
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        m.checking(new Expectations(){{
            allowing(image).getName(); will(returnValue("image-1-name"));
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getRunningInstanceCount(); will(returnValue(1));
            allowing(image).getInstances(); will(returnValue(Collections.singletonList(statusInstance(InstanceStatus.RUNNING))));
            allowing(image).getInstanceLimit(); will(returnValue(1));
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        KubeCloudClient cloudClient = createClient(images);
        assertFalse(cloudClient.canStartNewInstance(image));
    }

    public void testDuplicateImageName() throws Exception {
        KubeCloudImage image1 = m.mock(KubeCloudImage.class, "1");
        KubeCloudImage image2 = m.mock(KubeCloudImage.class, "2");
        m.checking(new Expectations(){{
            allowing(image1).getId(); will(returnValue("image-1-id"));
            allowing(image1).getName(); will(returnValue("image"));
            allowing(image1).getRunningInstanceCount(); will(returnValue(0));
            allowing(image2).getId(); will(returnValue("image-2-id"));
            allowing(image2).getName(); will(returnValue("image"));
            allowing(image2).getRunningInstanceCount(); will(returnValue(0));
        }});
        createClient(Arrays.asList(image1, image2));
    }

    // ------------------------------------------------------------------
    // Quota accounting: per-status occupancy table
    // ------------------------------------------------------------------

    /** Statuses that must occupy a quota slot, see docs/instance-quota.md. */
    private static final EnumSet<InstanceStatus> QUOTA_OCCUPYING_STATUSES = EnumSet.of(
      InstanceStatus.SCHEDULED_TO_START,
      InstanceStatus.STARTING,
      InstanceStatus.RESTARTING,
      InstanceStatus.RUNNING,
      InstanceStatus.SCHEDULED_TO_STOP,
      InstanceStatus.STOPPING
    );

    public void testQuotaStatusTable_classificationMatchesDocumentedTable() {
        for (InstanceStatus status : InstanceStatus.values()) {
            boolean occupies = QUOTA_OCCUPYING_STATUSES.contains(status);
            then(InstanceQuotaSnapshot.occupiesQuota(status))
              .as("status " + status.name() + " occupancy mismatch")
              .isEqualTo(occupies);
        }

        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.RUNNING)).isEqualTo(InstanceQuotaSnapshot.Group.RUNNING);
        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.SCHEDULED_TO_START)).isEqualTo(InstanceQuotaSnapshot.Group.STARTING);
        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.STARTING)).isEqualTo(InstanceQuotaSnapshot.Group.STARTING);
        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.RESTARTING)).isEqualTo(InstanceQuotaSnapshot.Group.STARTING);
        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.SCHEDULED_TO_STOP)).isEqualTo(InstanceQuotaSnapshot.Group.TERMINATING);
        then(InstanceQuotaSnapshot.groupOf(InstanceStatus.STOPPING)).isEqualTo(InstanceQuotaSnapshot.Group.TERMINATING);
        for (InstanceStatus status : Arrays.asList(InstanceStatus.UNKNOWN, InstanceStatus.STOPPED,
          InstanceStatus.ERROR, InstanceStatus.ERROR_CANNOT_STOP)) {
            then(InstanceQuotaSnapshot.groupOf(status))
              .as(status.name() + " must not be counted")
              .isEqualTo(InstanceQuotaSnapshot.Group.NOT_COUNTED);
        }
        then(InstanceQuotaSnapshot.groupOf(null)).isEqualTo(InstanceQuotaSnapshot.Group.NOT_COUNTED);
    }

    /**
     * Cross-checks the table against the real production wiring
     * {@code KubeCloudImageImpl#addStartedInstance/getInstances} and confirms the historical
     * {@code getRunningInstanceCount()} predicate still excludes terminating states.
     */
    public void testRealImage_countingWiringMatchesTable() {
        Map<String, String> imageParameters = new HashMap<>();
        imageParameters.put(KubeParametersConstants.IMAGE_INSTANCE_LIMIT, "10");
        KubeCloudImageImpl realImage = new KubeCloudImageImpl(
          new KubeCloudImageData(new MapBackedImageParameters(imageParameters)), myApi);

        for (InstanceStatus status : InstanceStatus.values()) {
            Pod pod = new Pod();
            ObjectMeta metadata = new ObjectMeta();
            metadata.setName("real-instance-" + status.name());
            pod.setMetadata(metadata);
            KubeCloudInstanceImpl instance = new KubeCloudInstanceImpl(realImage, pod);
            instance.setStatus(status);
            realImage.addStartedInstance(instance);
        }

        // isStartingOrStarted family only: SCHEDULED_TO_START, STARTING, RESTARTING, RUNNING
        then(realImage.getRunningInstanceCount()).isEqualTo(4);

        InstanceQuotaSnapshot snapshot = InstanceQuotaSnapshot.of(realImage.getInstances());
        then(snapshot.getRunning()).isEqualTo(1);
        then(snapshot.getStarting()).isEqualTo(3);
        then(snapshot.getTerminating()).isEqualTo(2);
        then(snapshot.getOccupied()).isEqualTo(6);

        KubeCloudClient atLimit = createClient(Collections.singletonList(realImage),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "6")));
        CanStartNewInstanceResult blocked = atLimit.canStartNewInstanceWithDetails(realImage);
        then(blocked.isPositive()).isFalse();
        then(blocked.getReason()).contains("occupied=6", "terminating=2");

        KubeCloudClient aboveLimit = createClient(Collections.singletonList(realImage),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "7")));
        then(aboveLimit.canStartNewInstanceWithDetails(realImage).isPositive()).isTrue();
    }

    /**
     * One jmock instance double per {@link InstanceStatus}: with profile limit 1, a single
     * occupying instance must block start with QUOTA_LIMIT_REACHED, while a non-occupying one
     * must not.
     */
    public void testCanStart_newInstance_statusByStatusBoundary() {
        for (InstanceStatus status : InstanceStatus.values()) {
            KubeCloudInstance instance = statusInstance(status);
            KubeCloudImage image = quotaImage("image-for-" + status.name(),
              Collections.singletonList(instance), -1);
            KubeCloudClient client = createClient(
              Collections.singletonList(image),
              new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "1")));

            CanStartNewInstanceResult result = client.canStartNewInstanceWithDetails(image);
            if (QUOTA_OCCUPYING_STATUSES.contains(status)) {
                then(result.isPositive()).as(status.name() + " should occupy quota").isFalse();
                then(result.getReasonType())
                  .as(status.name() + " should be quota limit")
                  .isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
                then(result.getReason()).as(status.name() + " message should carry counts").contains("occupied=1");
            } else {
                then(result.isPositive())
                  .as(status.name() + " should not occupy quota: " + result.getReason())
                  .isTrue();
            }
        }
    }

    public void testProfileLimit_sumsOccupancyAcrossImagesFromOneSnapshot() {
        KubeCloudImage image1 = quotaImage("profile-image-1",
          Collections.singletonList(statusInstance(InstanceStatus.RUNNING)), -1);
        KubeCloudImage image2 = quotaImage("profile-image-2",
          Collections.singletonList(statusInstance(InstanceStatus.STOPPING)), -1);

        // 1 running + 1 terminating = 2 occupied, profile limit 2 -> rejected
        KubeCloudClient fullClient = createClient(Arrays.asList(image1, image2),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "2")));
        CanStartNewInstanceResult full = fullClient.canStartNewInstanceWithDetails(image1);
        then(full.isPositive()).isFalse();
        then(full.getReasonType()).isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
        then(full.getReason()).contains("per profile", "out of 2",
          "occupied=2", "running=1", "starting=0", "terminating=1");

        // Same occupancy, profile limit 3 -> profile check passes; image1 has its limit disabled
        KubeCloudClient roomyClient = createClient(Arrays.asList(image1, image2),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "3")));
        then(roomyClient.canStartNewInstanceWithDetails(image1).isPositive()).isTrue();
    }

    public void testImageLimit_countsTerminatingAndUsesSnapshotNumbers() {
        List<KubeCloudInstance> instances = Arrays.asList(
          statusInstance(InstanceStatus.RUNNING),
          statusInstance(InstanceStatus.SCHEDULED_TO_STOP));
        KubeCloudImage image = quotaImage("image-limit-terminating", instances, 2);
        KubeCloudClient client = createClient(Collections.singletonList(image));

        CanStartNewInstanceResult result = client.canStartNewInstanceWithDetails(image);
        then(result.isPositive()).isFalse();
        then(result.getReasonType()).isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
        then(result.getReason()).contains("per image", "image-limit-terminating", "out of 2",
          "occupied=2", "running=1", "starting=0", "terminating=1");
    }

    public void testQuotaMessage_numbersMatchSnapshotExactly() {
        List<KubeCloudInstance> instances = new ArrayList<>();
        // 2 running
        instances.add(statusInstance(InstanceStatus.RUNNING));
        instances.add(statusInstance(InstanceStatus.RUNNING));
        // 3 starting-family
        instances.add(statusInstance(InstanceStatus.SCHEDULED_TO_START));
        instances.add(statusInstance(InstanceStatus.STARTING));
        instances.add(statusInstance(InstanceStatus.RESTARTING));
        // 2 terminating
        instances.add(statusInstance(InstanceStatus.SCHEDULED_TO_STOP));
        instances.add(statusInstance(InstanceStatus.STOPPING));
        // terminal/error instances must not be counted
        instances.add(statusInstance(InstanceStatus.STOPPED));
        instances.add(statusInstance(InstanceStatus.ERROR));
        instances.add(statusInstance(InstanceStatus.ERROR_CANNOT_STOP));
        instances.add(statusInstance(InstanceStatus.UNKNOWN));

        InstanceQuotaSnapshot snapshot = InstanceQuotaSnapshot.of(instances);
        then(snapshot.getRunning()).isEqualTo(2);
        then(snapshot.getStarting()).isEqualTo(3);
        then(snapshot.getTerminating()).isEqualTo(2);
        then(snapshot.getOccupied()).isEqualTo(7);

        KubeCloudImage image = quotaImage("counted-image", instances, -1);
        KubeCloudClient client = createClient(Collections.singletonList(image),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "5")));

        CanStartNewInstanceResult result = client.canStartNewInstanceWithDetails(image);
        then(result.isPositive()).isFalse();
        then(result.getReasonType()).isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
        String reason = result.getReason();
        then(reason).contains("out of 5");

        // Extract the stable key=value count segment and require every number to equal the snapshot.
        Map<String, Integer> messageCounts = extractCountTokens(reason);
        then(messageCounts).containsEntry("occupied", snapshot.getOccupied());
        then(messageCounts).containsEntry("running", snapshot.getRunning());
        then(messageCounts).containsEntry("starting", snapshot.getStarting());
        then(messageCounts).containsEntry("terminating", snapshot.getTerminating());
    }

    public void testZeroLimit_forbidsStartEvenWithNoInstances() {
        KubeCloudImage image = quotaImage("zero-profile-image", Collections.emptyList(), -1);
        KubeCloudClient profileClient = createClient(Collections.singletonList(image),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "0")));
        CanStartNewInstanceResult profileResult = profileClient.canStartNewInstanceWithDetails(image);
        then(profileResult.isPositive()).isFalse();
        then(profileResult.getReasonType()).isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
        then(profileResult.getReason()).contains("limit is 0", "occupied=0");

        KubeCloudImage zeroImage = quotaImage("zero-image-limit-image", Collections.emptyList(), 0);
        KubeCloudClient imageClient = createClient(Collections.singletonList(zeroImage));
        CanStartNewInstanceResult imageResult = imageClient.canStartNewInstanceWithDetails(zeroImage);
        then(imageResult.isPositive()).isFalse();
        then(imageResult.getReasonType()).isEqualTo(CanStartNewInstanceResult.ReasonType.QUOTA_LIMIT_REACHED);
        then(imageResult.getReason()).contains("per image", "limit is 0");
    }

    public void testNegativeAndEmptyLimitsMeanUnlimited() {
        KubeCloudImage image = quotaImage("unlimited-image",
          Collections.singletonList(statusInstance(InstanceStatus.RUNNING)), -7);
        KubeCloudClient negativeProfileClient = createClient(Collections.singletonList(image),
          new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, "-3")));
        then(negativeProfileClient.canStartNewInstanceWithDetails(image).isPositive()).isTrue();

        KubeCloudClient emptyProfileClient = createClient(Collections.singletonList(image),
          new MockCloudClientParameters(Collections.emptyMap()));
        then(emptyProfileClient.canStartNewInstanceWithDetails(image).isPositive()).isTrue();
    }

    public void testInvalidProfileLimit_returnsReadableNegativeResultInsteadOfThrowing() {
        for (String badValue : Arrays.asList("3a", " 3")) {
            KubeCloudImage image = quotaImage("bad-limit-image-" + badValue.hashCode(),
              Collections.emptyList(), -1);
            KubeCloudClient client = createClient(Collections.singletonList(image),
              new MockCloudClientParameters(Collections.singletonMap(PROFILE_INSTANCE_LIMIT, badValue)));

            CanStartNewInstanceResult result;
            try {
                result = client.canStartNewInstanceWithDetails(image);
            } catch (RuntimeException e) {
                throw new AssertionError("Invalid limit '" + badValue + "' must not crash canStart", e);
            }
            then(result.isPositive()).as("limit '" + badValue + "' must be rejected").isFalse();
            then(result.getReason()).contains(badValue, "integer");
        }
    }

    public void testImageData_invalidLimitThrowsReadableException() {
        KubeCloudImageData imageData = new KubeCloudImageData(
          new MapBackedImageParameters(Collections.singletonMap(KubeParametersConstants.IMAGE_INSTANCE_LIMIT, "3a")));
        try {
            imageData.getInstanceLimit();
            fail("Expected InvalidInstanceLimitException");
        } catch (InvalidInstanceLimitException e) {
            then(e.getMessage()).contains("3a", "integer");
        }

        then(new KubeCloudImageData(new MapBackedImageParameters(
          Collections.singletonMap(KubeParametersConstants.IMAGE_INSTANCE_LIMIT, "3"))).getInstanceLimit()).isEqualTo(3);
        then(new KubeCloudImageData(new MapBackedImageParameters(
          Collections.singletonMap(KubeParametersConstants.IMAGE_INSTANCE_LIMIT, "-4"))).getInstanceLimit()).isEqualTo(-4);
        then(new KubeCloudImageData(new MapBackedImageParameters(Collections.emptyMap())).getInstanceLimit()).isEqualTo(-1);
    }

    /** Minimal map-backed {@link CloudImageParameters} for parser tests (jmock only mocks interfaces here). */
    private static class MapBackedImageParameters extends CloudImageParameters {
        private final Map<String, String> myParameters;

        MapBackedImageParameters(Map<String, String> parameters) {
            myParameters = parameters;
        }

        @Override
        @Nullable
        public String getInternalId() {
            return "internal-id";
        }

        @Override
        @Nullable
        public String getProjectId() {
            return "project-id";
        }

        @Override
        @NotNull
        public String getId() {
            return "map-image-id";
        }

        @Override
        public Integer getAgentPoolId() {
            return null;
        }

        @Override
        @Nullable
        public String getProfileId() {
            return "profile-id";
        }

        @Override
        public String getParameter(String key) {
            return myParameters.get(key);
        }

        @Override
        @NotNull
        public Set<String> getParameterNames() {
            return myParameters.keySet();
        }

        @Override
        @NotNull
        public Map<String, String> getParameters() {
            return myParameters;
        }
    }

    @NotNull
    private static Map<String, Integer> extractCountTokens(@NotNull String reason) {
        Matcher matcher = Pattern.compile("(occupied|running|starting|terminating)=(\\d+)").matcher(reason);
        Map<String, Integer> counts = new HashMap<>();
        while (matcher.find()) {
            counts.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return counts;
    }

    private int myStatusInstanceCounter = 0;

    @NotNull
    private KubeCloudInstance statusInstance(@NotNull final InstanceStatus status) {
        final KubeCloudInstance instance = m.mock(KubeCloudInstance.class,
          "status-instance-" + status.name() + "-" + (myStatusInstanceCounter++));
        m.checking(new Expectations() {{
            allowing(instance).getStatus();
            will(returnValue(status));
        }});
        return instance;
    }

    @NotNull
    private KubeCloudImage quotaImage(@NotNull final String id,
                                      @NotNull final List<KubeCloudInstance> instances,
                                      final int imageLimit) {
        final KubeCloudImage image = m.mock(KubeCloudImage.class, "quota-image-" + id);
        m.checking(new Expectations() {{
            allowing(image).getId();
            will(returnValue(id));
            allowing(image).getName();
            will(returnValue(id + "-name"));
            allowing(image).getInstances();
            will(returnValue(instances));
            allowing(image).getInstanceLimit();
            will(returnValue(imageLimit));
        }});
        return image;
    }

    public void start_new_instance_failure_pvc() throws InterruptedException {
        final String podSpecMode = "DUMMY_SPEC_MODE";
        final CloudInstanceUserData userData = createInstanceTag();
        final BuildAgentPodTemplateProvider provider = m.mock(BuildAgentPodTemplateProvider.class);
        KubeCloudImage image = m.mock(KubeCloudImage.class);
        KubeCloudClientParametersImpl parameters = KubeCloudClientParametersImpl.create(new MockCloudClientParameters(Collections.emptyMap()));
        Pod podTemplate = new Pod();
        ObjectMeta podMetadata = new ObjectMeta();
        podMetadata.setName("image-1-id-123");
        podTemplate.setMetadata(podMetadata);
        podMetadata.setLabels(new HashMap<>());

        PersistentVolumeClaim pvcTemplate = new PersistentVolumeClaim();
        PersistentVolumeClaim newPVC = new PersistentVolumeClaim();
        {
            ObjectMeta pvcMetadata = new ObjectMeta();
            pvcMetadata.setName("image-1-id-123");
            pvcTemplate.setMetadata(pvcMetadata);
        }
        {
            ObjectMeta pvcMetadata = new ObjectMeta();
            pvcMetadata.setName("image-1-id-123");
            newPVC.setMetadata(pvcMetadata);
        }

        KubernetesClientException podCreationException = new KubernetesClientException("Some error on pod creation");

        myPodTemplateProvidersMap.put(podSpecMode, provider);

        m.checking(new Expectations(){{
            allowing(image).getName(); will(returnValue("image-1-name"));
            allowing(image).getId(); will(returnValue("image-1-id"));
            allowing(image).getPodSpecMode(); will(returnValue(podSpecMode));
            allowing(image).addStartedInstance(with(any(KubeCloudInstance.class)));
            allowing(myApi).getNamespace(); will(returnValue("leer"));
            oneOf(provider).getPodTemplate("image-1-id-123", userData, image, myApi); will(returnValue(podTemplate));
            oneOf(provider).getPVC("image-1-id-123", image); will(returnValue(pvcTemplate));
            oneOf(myApi).createPVC(pvcTemplate); will(returnValue(newPVC));
            oneOf(myApi).createPod(podTemplate); will(throwException(podCreationException));
            oneOf(myApi).deletePVC("image-1-id-123"); // should delete PVC if can't create pod
        }});
        List<KubeCloudImage> images = Collections.singletonList(image);
        KubeCloudClient cloudClient = createClient("defaultServerUuid", "defaultProfileId", images, parameters);
        try {
            cloudClient.startNewInstance(image, userData);
            fail("An exception " + podCreationException + " should have been rethrown");
        } catch (Exception ex) {
            then(ex).isEqualTo(podCreationException);
        }
        m.assertIsSatisfied();
    }

    @NotNull
    private KubeCloudClient createClient(List<KubeCloudImage> images) {
        return createClient("defaultServerUuid", "defaultProfileId", images, new MockCloudClientParameters(Collections.emptyMap()));
    }

    @NotNull
    private KubeCloudClient createClient(List<KubeCloudImage> images, CloudClientParameters cloudClientParameters) {
        return createClient("defaultServerUuid", "defaultProfileId", images, cloudClientParameters);
    }

    @NotNull
    private KubeCloudClient createClient(String serverUuid, String profileId, List<KubeCloudImage> images, CloudClientParameters cloudClientParameters) {
        return createClient(serverUuid, profileId, images, new KubeCloudClientParametersImpl(cloudClientParameters));
    }
    @NotNull
    private KubeCloudClient createClient(String serverUuid, String profileId, List<KubeCloudImage> images, KubeCloudClientParameters parameters) {
        return new KubeCloudClient(myApi, serverUuid, profileId, images, parameters, myUpdater,
                                   myPodTemplateProviders, myExecutorService, image -> String.format("%s-123", image.getId()));
    }

    private CloudInstanceUserData createInstanceTag() {
        return new CloudInstanceUserData("agent name", "auth token", "server address", null, "profile id", "profile description", Collections.emptyMap());
    }

    private static class InProcessExecutor extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        @NotNull
        public List<Runnable> shutdownNow() {
            return null;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
            return true;
        }

        @Override
        public void execute(@NotNull final Runnable command) {
            command.run();
        }

        @NotNull
        @Override
        public Future<?> submit(@NotNull Runnable task) {
            execute(task);
            return new CompletableFuture<>();
        }
    }


    private class MockCloudClientParameters extends CloudClientParameters {
        private final Map<String, String> myParameters;

        public MockCloudClientParameters(Map<String, String> parameters) {
            myParameters = parameters;
        }

        @Nullable
        @Override
        public String getParameter(@NotNull String s) {
            return myParameters.get(s);
        }

        @NotNull
        @Override
        public Collection<String> listParameterNames() {
            return myParameters.keySet();
        }

        @NotNull
        @Override
        public Collection<CloudImageParameters> getCloudImages() {
            return null;
        }

        @NotNull
        @Override
        public Map<String, String> getParameters() {
            return Collections.unmodifiableMap(myParameters);
        }

        @NotNull
        @Override
        public String getProfileId() {
            return null;
        }

        @NotNull
        @Override
        public String getProfileDescription() {
            return null;
        }
    }
}