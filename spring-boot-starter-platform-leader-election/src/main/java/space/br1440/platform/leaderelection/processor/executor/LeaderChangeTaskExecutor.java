package space.br1440.platform.leaderelection.processor.executor;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import space.br1440.platform.leaderelection.util.ThreadUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LeaderChangeTaskExecutor implements DisposableBean {

    private final ExecutorService leaderChangeAsyncExecutor;
    private volatile boolean shutdown = false;

    public LeaderChangeTaskExecutor() {
        this.leaderChangeAsyncExecutor = createLeaderChangeAsyncExecutor();
    }

    public ExecutorService getLeaderChangeAsyncExecutor() {
        if (shutdown) {
            throw new IllegalStateException("LeaderChangeTaskExecutor is shutdown");
        }

        return leaderChangeAsyncExecutor;
    }

    @Override
    public void destroy() {
        if (shutdown) {
            return;
        }

        shutdown = true;
        ThreadUtils.shutdownExecutor(leaderChangeAsyncExecutor, Duration.ofSeconds(30));
    }

    private ExecutorService createLeaderChangeAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("platform-leader-change-async-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.initialize();

        return executor.getThreadPoolExecutor();
    }

}
