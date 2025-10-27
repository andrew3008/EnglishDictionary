package space.br1440.platform.leaderelection.testutil.support;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.concurrent.TimeUnit;

public class RedisTestSupport {

    private final RedisConnectionFactory connectionFactory;
    private volatile StringRedisTemplate redisTemplate;

    public RedisTestSupport(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public StringRedisTemplate getRedisTemplate() {
        if (redisTemplate == null) {
            synchronized (this) {
                if (redisTemplate == null) {
                    redisTemplate = createRedisTemplate();
                }
            }
        }
        return redisTemplate;
    }

    public void flushAll() {
        try (RedisConnection conn = connectionFactory.getConnection()) {
            conn.serverCommands().flushAll();
        }
    }

    public String get(String key) {
        return getRedisTemplate().opsForValue().get(key);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(getRedisTemplate().delete(key));
    }

    public boolean exists(String key) {
        return Boolean.TRUE.equals(getRedisTemplate().hasKey(key));
    }

    public long getTtl(String key) {
        Long ttl = getRedisTemplate().getExpire(key, TimeUnit.SECONDS);
        return (ttl != null) ? ttl : -2;
    }

    private StringRedisTemplate createRedisTemplate() {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

}
