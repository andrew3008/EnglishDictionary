package com.englishDictionary.utils;

import java.util.LinkedHashMap;

public class LRUCache<K, V> {
    private LRUCacheInner<K, V> cache;

    private class LRUCacheInner<K, V> extends LinkedHashMap<K, V> {
        private int cacheSize;

        public LRUCacheInner(int size) {
            super(size, 0.75f, true);
            cacheSize = size;
        }

        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
            return size() >= cacheSize;
        }
    }

    public LRUCache(int size) {
        cache = new LRUCacheInner(size);
    }

    public void put(K key, V value) {
        cache.put(key, value);
    }

    public V get(K key) {
        return cache.get(key);
    }
}
