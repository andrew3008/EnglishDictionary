package space.br1440.platform.leaderelection.lock.persistence.redis.script;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LeaseScripts {

    public static final String CREATE_LEASE_SCRIPT = """
        local key = KEYS[1]
        local identity = ARGV[1]
        local ttl = tonumber(ARGV[2])
        local timestamp = ARGV[3]
        local lease_duration = tonumber(ARGV[4])

        if ttl <= 0 or ttl > 300 then
            return cjson.encode({
                status = "error",
                error = "TTL must be between 1 and 300 seconds"
            })
        end

        if redis.call('EXISTS', key) == 1 then
            local data = redis.call('HMGET', key, 'holder', 'version')
            local ttl_remaining = redis.call('TTL', key)

            return cjson.encode({
                status = "conflict",
                current_holder = data[1],
                current_version = tonumber(data[2]) or 0,
                ttl_remaining = ttl_remaining
            })
        end

        -- Атомарное создание всех полей одним HSET
        redis.call('HSET', key, 'holder', identity, 'version', 1, 'acquire_time', timestamp, 'renew_time', timestamp, 'lease_duration', lease_duration, 'transitions', 0)
        redis.call('EXPIRE', key, ttl)

        return cjson.encode({
            status = "created",
            version = 1,
            timestamp = timestamp
        })
        """;

    public static final String GET_LEASE_INFO_SCRIPT = """
        local key = KEYS[1]
        local refresh_ttl = tonumber(ARGV[1])

        if redis.call('EXISTS', key) == 0 then
            return cjson.encode({
                status = "not_found"
            })
        end

        local data = redis.call('HMGET', key, 'holder', 'version', 'acquire_time', 'renew_time', 'lease_duration', 'transitions')
        local ttl = redis.call('TTL', key)

        -- Проверка race condition: ключ истёк между EXISTS и HMGET
        if ttl == -2 then
            return cjson.encode({
                status = "not_found"
            })
        end

        if refresh_ttl and refresh_ttl > 0 then
            redis.call('EXPIRE', key, refresh_ttl)
            ttl = refresh_ttl
        end

        return cjson.encode({
            status = "found",
            holder = data[1],
            version = tonumber(data[2]),
            acquire_time = data[3],
            renew_time = data[4],
            lease_duration = tonumber(data[5]),
            transitions = tonumber(data[6]) or 0,
            ttl_remaining = ttl
        })
        """;

    public static final String UPDATE_LEASE_SCRIPT = """
        local key = KEYS[1]
        local identity = ARGV[1]
        local expected_version = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])
        local timestamp = ARGV[4]
        local new_duration = tonumber(ARGV[5])

        if ttl <= 0 or ttl > 300 then
            return cjson.encode({
                status = "error",
                error = "TTL must be between 1 and 300 seconds"
            })
        end

        if redis.call('EXISTS', key) == 0 then
            return cjson.encode({
                status = "not_found"
            })
        end

        local data = redis.call('HMGET', key, 'holder', 'version', 'transitions', 'renew_time', 'lease_duration')
        local current_holder = data[1]
        local current_version = tonumber(data[2])
        local transitions = tonumber(data[3]) or 0
        local renew_time = tonumber(data[4]) or 0
        local lease_duration = tonumber(data[5]) or 0

        -- Проверяем истек ли lease
        local current_time = tonumber(timestamp)
        local lease_expiry = renew_time + (lease_duration * 1000000000) -- convert to nanoseconds
        local is_lease_expired = lease_expiry < current_time

        if current_holder ~= identity and not is_lease_expired then
            -- Другой владелец и lease еще валиден
            return cjson.encode({
                status = "ownership_mismatch",
                current_holder = current_holder,
                requested_by = identity
            })
        end

        if current_version ~= expected_version and not is_lease_expired then
            -- Версия не совпадает и lease еще валиден
            return cjson.encode({
                status = "version_mismatch",
                current_version = current_version,
                expected_version = expected_version
            })
        end

        local new_version = current_version + 1
        local new_transitions = transitions

        if current_holder ~= identity and is_lease_expired then
            -- Захват истекшего lease - меняем владельца и инкрементируем transitions
            new_transitions = transitions + 1
            redis.call('HSET', key, 'holder', identity, 'version', new_version, 
                      'acquire_time', timestamp, 'renew_time', timestamp, 
                      'lease_duration', new_duration or lease_duration, 'transitions', new_transitions)
        else
            -- Обычное обновление - не меняем transitions
            if new_duration then
                redis.call('HSET', key, 'version', new_version, 'renew_time', timestamp, 'lease_duration', new_duration)
            else
                redis.call('HSET', key, 'version', new_version, 'renew_time', timestamp)
            end
        end

        redis.call('EXPIRE', key, ttl)

        return cjson.encode({
            status = "renewed",
            version = new_version,
            timestamp = timestamp,
            transitions = new_transitions
        })
        """;

    public static final String DELETE_LEASE_SCRIPT = """
        local key = KEYS[1]
        local identity = ARGV[1]

        if redis.call('EXISTS', key) == 0 then
            return cjson.encode({
                status = "not_found"
            })
        end

        local data = redis.call('HMGET', key, 'holder', 'version')
        local current_holder = data[1]
        local version = tonumber(data[2])

        if current_holder ~= identity then
            return cjson.encode({
                status = "ownership_mismatch",
                current_holder = current_holder,
                requested_by = identity
            })
        end

        redis.call('DEL', key)

        return cjson.encode({
            status = "deleted",
            former_holder = identity,
            version = version
        })
        """;

    public static final String ACQUIRE_OR_RENEW_LEASE_SCRIPT = """
        local key = KEYS[1]
        local identity = ARGV[1]
        local ttl = tonumber(ARGV[2])
        local timestamp = ARGV[3]
        local lease_duration = tonumber(ARGV[4])

        if ttl <= 0 or ttl > 300 then
            return cjson.encode({
                status = "error",
                error = "TTL must be between 1 and 300 seconds"
            })
        end

        local exists = redis.call('EXISTS', key)

        if exists == 0 then
            -- Lease не существует - создаём новый
            redis.call('HSET', key, 'holder', identity, 'version', 1, 'acquire_time', timestamp, 'renew_time', timestamp, 'lease_duration', lease_duration, 'transitions', 0)
            redis.call('EXPIRE', key, ttl)

            return cjson.encode({
                status = "created",
                version = 1,
                transitions = 0,
                timestamp = timestamp
            })
        end

        -- Lease существует - проверяем владельца
        local data = redis.call('HMGET', key, 'holder', 'version', 'transitions')
        local current_holder = data[1]
        local current_version = tonumber(data[2])
        local transitions = tonumber(data[3]) or 0

        if current_holder == identity then
            -- Тот же владелец - продлеваем
            local new_version = current_version + 1
            redis.call('HSET', key, 'version', new_version, 'renew_time', timestamp, 'lease_duration', lease_duration)
            redis.call('EXPIRE', key, ttl)

            return cjson.encode({
                status = "renewed",
                version = new_version,
                transitions = transitions,
                timestamp = timestamp
            })
        else
            -- Другой владелец - проверяем истек ли lease
            local ttl_remaining = redis.call('TTL', key)
            local lease_data = redis.call('HMGET', key, 'renew_time', 'lease_duration')
            local renew_time = tonumber(lease_data[1]) or 0
            local lease_duration = tonumber(lease_data[2]) or 0

            -- Проверяем истек ли lease (renew_time + lease_duration < current_time)
            local current_time = tonumber(timestamp)
            local lease_expiry = renew_time + (lease_duration * 1000000000) -- convert to nanoseconds

            if lease_expiry < current_time then
                -- Lease истек - позволяем захват
                local new_version = current_version + 1
                local new_transitions = transitions + 1

                redis.call('HSET', key, 'holder', identity, 'version', new_version, 
                          'acquire_time', timestamp, 'renew_time', timestamp, 
                          'lease_duration', lease_duration, 'transitions', new_transitions)
                redis.call('EXPIRE', key, ttl)
                
                return cjson.encode({
                    status = "acquired",
                    version = new_version,
                    transitions = new_transitions,
                    timestamp = timestamp,
                    previous_holder = current_holder
                })
            else
                -- Lease еще валиден - возвращаем конфликт
                return cjson.encode({
                    status = "conflict",
                    current_holder = current_holder,
                    current_version = current_version,
                    ttl_remaining = ttl_remaining
                })
            end
        end
        """;

}
