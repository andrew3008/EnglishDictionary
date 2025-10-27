package space.br1440.platform.leaderelection.schedulerlocking.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.annotation.locking.DistributedLock;
import space.br1440.platform.leaderelection.annotation.locking.SchedulerLock;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.converter.DynamicParametersConverter;

import java.lang.reflect.Method;

@Slf4j
public class LockConfigurationExtractor {

    private final LeaderElectionProperties.LockSettings settings;
    private final DynamicParametersConverter dynamicParametersResolver;

    public LockConfigurationExtractor(LeaderElectionProperties properties,
                                      Environment environment,
                                      ApplicationContext applicationContext) {
        this.settings = properties.getLock();
        this.dynamicParametersResolver = new DynamicParametersConverter(environment, applicationContext);
    }

    public boolean hasLockAnnotation(Method method) {
        return method.isAnnotationPresent(SchedulerLock.class) ||
                method.isAnnotationPresent(DistributedLock.class);
    }

    public LockConfiguration extractLockConfiguration(Method method) {
        SchedulerLock schedulerLock = method.getAnnotation(SchedulerLock.class);
        if (schedulerLock != null) {
            return extractFromSchedulerLock(schedulerLock, method);
        } else {
            DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);
            return extractFromDistributedLock(distributedLock, method);
        }
    }

    private LockConfiguration extractFromSchedulerLock(SchedulerLock schedulerLock, Method method) {
        return extractLockConfiguration(
                schedulerLock.lockName(),
                schedulerLock.lockAtMostFor(),
                schedulerLock.lockAtLeastFor(),
                schedulerLock.keepAliveLockUntil(),
                method
        );
    }

    private LockConfiguration extractFromDistributedLock(DistributedLock distributedLock, Method method) {
        return extractLockConfiguration(
                distributedLock.lockName(),
                distributedLock.lockAtMostFor(),
                distributedLock.lockAtLeastFor(),
                distributedLock.keepAliveLockUntil(),
                method
        );
    }

    private LockConfiguration extractLockConfiguration(String leaseRole,
                                                       String lockAtMostFor,
                                                       String lockAtLeastFor,
                                                       String keepAliveLockUntil,
                                                       Method method) {
        DynamicParametersConverter.DynamicLockParams params = dynamicParametersResolver.resolveDynamicParams(
                method, leaseRole, lockAtMostFor, lockAtLeastFor, keepAliveLockUntil,
                settings.getDefaultLockAtMostFor(), settings.getDefaultLockAtLeastFor()
        );

        return new LockConfiguration(
                leaseRole,
                params.lockAtMostFor(),
                params.lockAtLeastFor(),
                params.keepAliveLockUntil()
        );
    }

}
