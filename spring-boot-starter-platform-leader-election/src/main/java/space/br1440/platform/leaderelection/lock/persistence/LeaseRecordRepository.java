package space.br1440.platform.leaderelection.lock.persistence;

import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;

import java.time.Duration;

public interface LeaseRecordRepository {

    boolean createRecord(String leaseKey, LeaseRecord record, Duration ttl);

    LeaseRecord getCurrentRecord(String leaseKey);

    boolean updateRecord(String leaseKey, LeaseRecord record, Duration ttl);

    boolean deleteRecord(String leaseKey, String identity);

    @SuppressWarnings("UnusedReturnValue")
    boolean deleteRecord(String leaseKey);

}
