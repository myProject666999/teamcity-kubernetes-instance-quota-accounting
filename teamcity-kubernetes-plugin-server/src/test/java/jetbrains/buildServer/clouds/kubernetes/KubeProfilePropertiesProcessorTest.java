
package jetbrains.buildServer.clouds.kubernetes;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.clouds.kubernetes.auth.KubeAuthStrategyProvider;
import jetbrains.buildServer.serverSide.InvalidProperty;
import org.jetbrains.annotations.NotNull;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static jetbrains.buildServer.clouds.CloudImageParameters.SOURCE_IMAGES_JSON;
import static jetbrains.buildServer.clouds.kubernetes.KubeParametersConstants.AUTH_STRATEGY;
import static jetbrains.buildServer.clouds.kubernetes.KubeParametersConstants.IMAGE_INSTANCE_LIMIT;
import static jetbrains.buildServer.clouds.kubernetes.KubeParametersConstants.PROFILE_INSTANCE_LIMIT;
import static org.assertj.core.api.BDDAssertions.then;

/**
 * Save-time validation of instance limit properties in {@link KubeProfilePropertiesProcessor}.
 */
@Test
public class KubeProfilePropertiesProcessorTest extends BaseTestCase {
    private Mockery m;
    private KubeAuthStrategyProvider myAuthStrategyProvider;
    private KubeProfilePropertiesProcessor myProcessor;

    @BeforeMethod
    public void setUp() throws Exception {
        super.setUp();
        m = new Mockery();
        myAuthStrategyProvider = m.mock(KubeAuthStrategyProvider.class);
        m.checking(new Expectations() {{
            allowing(myAuthStrategyProvider).find(with(any(String.class)));
            will(returnValue(null));
        }});
        myProcessor = new KubeProfilePropertiesProcessor(myAuthStrategyProvider);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        m.assertIsSatisfied();
        super.tearDown();
    }

    public void testInvalidProfileLimit_isReportedAsInvalidProperty() {
        for (String badValue : new String[]{"3a", " 3"}) {
            Map<String, String> parameters = new HashMap<>();
            parameters.put(PROFILE_INSTANCE_LIMIT, badValue);

            Collection<InvalidProperty> invalid = myProcessor.process(parameters);

            InvalidProperty limitProperty = findProperty(invalid, PROFILE_INSTANCE_LIMIT);
            then(limitProperty).as("value '" + badValue + "' must be invalid").isNotNull();
            then(limitProperty.getInvalidReason()).contains(badValue);
        }
    }

    public void testValidProfileLimits_areNotReported() {
        for (String validValue : new String[]{"", "0", "-1", "-5", "3"}) {
            Map<String, String> parameters = validAuthMap();
            parameters.put(PROFILE_INSTANCE_LIMIT, validValue);

            Collection<InvalidProperty> invalid = myProcessor.process(parameters);

            then(findProperty(invalid, PROFILE_INSTANCE_LIMIT))
              .as("value '" + validValue + "' should be valid").isNull();
        }
    }

    public void testInvalidImageLimit_inImagesJson_isReported() {
        Map<String, String> parameters = validAuthMap();
        parameters.put(SOURCE_IMAGES_JSON, "[{\"" + IMAGE_INSTANCE_LIMIT + "\":\"3a\"}]");

        Collection<InvalidProperty> invalid = myProcessor.process(parameters);

        InvalidProperty limitProperty = findProperty(invalid, IMAGE_INSTANCE_LIMIT);
        then(limitProperty).isNotNull();
        then(limitProperty.getInvalidReason()).contains("3a");
    }

    public void testValidImageLimits_areNotReported() {
        for (String validValue : new String[]{"", "0", "-2", "7"}) {
            Map<String, String> parameters = validAuthMap();
            parameters.put(SOURCE_IMAGES_JSON,
              "[{\"" + IMAGE_INSTANCE_LIMIT + "\":\"" + validValue + "\"}]");

            Collection<InvalidProperty> invalid = myProcessor.process(parameters);

            then(findProperty(invalid, IMAGE_INSTANCE_LIMIT))
              .as("image limit '" + validValue + "' should be valid; invalid=" + invalid)
              .isNull();
        }
    }

    public void testMissingLimits_keepExistingAuthErrorOnly() {
        Collection<InvalidProperty> invalid = myProcessor.process(Collections.emptyMap());
        then(findProperty(invalid, AUTH_STRATEGY)).isNotNull();
        then(findProperty(invalid, PROFILE_INSTANCE_LIMIT)).isNull();
        then(findProperty(invalid, IMAGE_INSTANCE_LIMIT)).isNull();
    }

    @NotNull
    private Map<String, String> validAuthMap() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put(AUTH_STRATEGY, "some-auth-strategy");
        return parameters;
    }

    @org.jetbrains.annotations.Nullable
    private static InvalidProperty findProperty(Collection<InvalidProperty> invalid, String name) {
        return invalid.stream().filter(p -> name.equals(p.getPropertyName())).findFirst().orElse(null);
    }
}
