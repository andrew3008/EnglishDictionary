package space.br1440.platform.leaderelection.lock;

public interface DistributedLockRegistry {

    DistributedLock obtain(String lockKey);

}
