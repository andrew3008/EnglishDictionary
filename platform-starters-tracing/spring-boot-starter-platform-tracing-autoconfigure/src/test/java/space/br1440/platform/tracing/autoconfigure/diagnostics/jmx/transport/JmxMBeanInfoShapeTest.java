package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenMBeanOperationInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfo;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;
import javax.management.openmbean.OpenType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class JmxMBeanInfoShapeTest {

    @Test
    void acceptsExactGeneratedMutationOpenTypes() {
        // given
        MBeanInfo info = info(mutationOperation(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE));

        // when
        // then
        assertThatCode(() -> validateMutation(info)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMutationRequestOpenTypeDrift() {
        // given
        MBeanInfo info = info(mutationOperation(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.RUNTIME_POLICY,
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE));

        // when
        // then
        assertMutationViolation(info);
    }

    @Test
    void rejectsMutationResponseOpenTypeDrift() {
        // given
        MBeanInfo info = info(mutationOperation(
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.RUNTIME_POLICY));

        // when
        // then
        assertMutationViolation(info);
    }

    @Test
    void rejectsMutationParameterNameDrift() {
        // given
        MBeanInfo info = info(mutationOperation(
                "unexpectedRequest",
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE));

        // when
        // then
        assertMutationViolation(info);
    }

    private static void validateMutation(MBeanInfo info) {
        JmxMBeanInfoShape.requireMutation(
                info,
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                JmxWireContract.PARAMETER_SETTINGS_MUTATION_APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_REQUEST,
                JmxOpenTypes.APPLY_SNAPSHOT_RESPONSE);
    }

    private static OpenMBeanOperationInfo mutationOperation(String parameterName,
                                                            OpenType<?> parameterType,
                                                            OpenType<?> returnType) {
        OpenMBeanParameterInfo parameter = new OpenMBeanParameterInfoSupport(
                parameterName,
                "request",
                parameterType);

        return new OpenMBeanOperationInfoSupport(
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                "apply",
                new OpenMBeanParameterInfo[]{parameter},
                returnType,
                MBeanOperationInfo.ACTION
        );
    }

    private static MBeanInfo info(OpenMBeanOperationInfo operation) {
        return new OpenMBeanInfoSupport(
                "fixture",
                "fixture",
                new OpenMBeanAttributeInfo[0],
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[]{operation},
                new MBeanNotificationInfo[0]);
    }

    private void assertMutationViolation(MBeanInfo info) {
        assertThatThrownBy(() -> validateMutation(info))
                .isInstanceOf(JmxMBeanInfoShapeViolationException.class)
                .satisfies(error -> assertThat(((JmxMBeanInfoShapeViolationException) error).failureCode())
                        .isEqualTo(JmxMBeanInfoShapeViolationException.FAILURE_CODE));
    }
}
