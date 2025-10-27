package space.br1440.platform.leaderelection.lock;

import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;

import java.time.Duration;
import java.util.concurrent.locks.Lock;

public interface DistributedLock extends Lock {

    void lock(Duration ttl) throws InterruptedException;

    LeaseRecord getCurrentRecord();

    boolean createRecord(LeaseRecord record);

    boolean updateRecord(LeaseRecord record);

}
