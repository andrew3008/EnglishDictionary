package space.br1440.platform.leaderelection.schedulerlocking;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.aop.support.RootClassFilter;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;

public class TaskSchedulerPointcut implements Pointcut {

    @Override
    @NonNull
    public ClassFilter getClassFilter() {
        return new RootClassFilter(TaskScheduler.class);
    }

    @Override
    @NonNull
    public MethodMatcher getMethodMatcher() {
        NameMatchMethodPointcut nameMatchMethodPointcut = new NameMatchMethodPointcut();
        nameMatchMethodPointcut.setMappedNames("schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay");
        return nameMatchMethodPointcut;
    }

}
