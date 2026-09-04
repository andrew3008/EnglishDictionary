package space.br1440.platform.leaderelection.leaderelection;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;
import space.br1440.platform.leaderelection.util.ThreadUtils;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class LeaderElectorInitiator implements SmartLifecycle, DisposableBean {

    private final LeaderElector leaderElector;
    private final ExecutorService executorService;
    private final AtomicReference<CompletableFuture<Void>> currentTask = new AtomicReference<>();
    private final Lock lock = new ReentrantLock();
    private volatile boolean running = false;

    public LeaderElectorInitiator(LeaderElectionProperties properties,
                                  Environment environment,
                                  LeaderElector leaderElector) {
        this.leaderElector = leaderElector;

        String leadershipName = LeadershipNameResolver.resolve(properties, environment);
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "LeaderElector-" + leadershipName);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (running) {
                throw new IllegalStateException("LeaderElectorInitiator is already running");
            }

            running = true;

            CompletableFuture<Void> task = CompletableFuture.runAsync(
                    () -> leaderElector.runLeaderElection(this::isRunning),
                    executorService
            );
            currentTask.set(task);

            task.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("Leader election failed", throwable);
                }
                running = false;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            if (running) {
                running = false;
                leaderElector.stop();

                CompletableFuture<Void> task = currentTask.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void destroy() {
        stop();
        ThreadUtils.shutdownExecutor(executorService, Duration.ofSeconds(30));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

}

