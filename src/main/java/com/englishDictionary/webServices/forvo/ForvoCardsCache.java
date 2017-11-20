package com.englishDictionary.webServices.forvo;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.expiry.Duration;
import org.ehcache.expiry.Expirations;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ForvoCardsCache {
    private Cache<String, Object> cache;

    public ForvoCardsCache(int maxNumItems, int itemLiveExpirationHours) {
        CacheConfiguration<String, Object> cacheConfiguration =
                CacheConfigurationBuilder.newCacheConfigurationBuilder(String.class, Object.class, ResourcePoolsBuilder.heap(maxNumItems))
                        .withExpiry(Expirations.timeToLiveExpiration(Duration.of(itemLiveExpirationHours, TimeUnit.HOURS)))
                        .build();
        CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder().withCache("forvoCardsCacheConfiguration", cacheConfiguration).build();
        cacheManager.init();
        cache = cacheManager.createCache("forvoCardsCache", cacheConfiguration);
    }

    public void put(String key, List<ForvoCard> value) {
        cache.put(key, value);
    }

    public List<ForvoCard> get(String key) {
        return (List<ForvoCard>)cache.get(key);
    }
}
