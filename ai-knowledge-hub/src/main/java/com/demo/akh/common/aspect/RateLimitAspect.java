package com.demo.akh.common.aspect;

import com.demo.akh.common.annotation.RateLimit;
import com.demo.akh.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisTemplate<String, Object> redisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String ip = attrs != null ? attrs.getRequest().getRemoteAddr() : "unknown";
        String key = "akh:rate:" + pjp.getSignature().toShortString() + ":" + ip;

        Long count = redisTemplate.opsForValue().increment(key, 1);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, rateLimit.window(), TimeUnit.SECONDS);
        }
        if (count != null && count > rateLimit.limit()) {
            throw new BizException(4029, rateLimit.message());
        }
        return pjp.proceed();
    }
}