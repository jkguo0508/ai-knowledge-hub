package com.demo.akh.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> clazz) {
        Object v = redisTemplate.opsForValue().get(key);
        return v == null ? null : (T) v;
    }

    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }

    /** 分布式锁：SETNX + 过期时间（原子操作） */
    public boolean tryLock(String key, String requestId, long expireSeconds) {
        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, requestId, expireSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /** 释放锁：必须先判断是不是自己的锁（生产上应用 Lua 脚本保证原子性） */
    public void unlock(String key, String requestId) {
        Object v = redisTemplate.opsForValue().get(key);
        if (requestId.equals(v)) {
            redisTemplate.delete(key);
        }
    }
}