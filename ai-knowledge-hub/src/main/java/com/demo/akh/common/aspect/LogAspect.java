package com.demo.akh.common.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Aspect
@Component
public class LogAspect {

    private final ObjectMapper mapper = new ObjectMapper();

    // 切入点：controller 包下所有方法
    @Pointcut("execution(* com.demo.akh.controller..*.*(..))")
    public void controllerPointcut() {}

    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String method = pjp.getSignature().toShortString();

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String uri = attrs != null ? attrs.getRequest().getRequestURI() : "-";
        String httpMethod = attrs != null ? attrs.getRequest().getMethod() : "-";

        try {
            log.info("==> {} {} | {} | args={}", httpMethod, uri, method, safeJson(pjp.getArgs()));
            Object result = pjp.proceed();
            log.info("<== {} {} | 耗时 {}ms", httpMethod, uri, System.currentTimeMillis() - start);
            return result;
        } catch (Throwable e) {
            log.error("!!! {} {} | 异常 {}ms | {}", httpMethod, uri,
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    private String safeJson(Object[] args) {
        try {
            // 注意：MultipartFile / HttpServletRequest 等不能序列化，要过滤
            return mapper.writeValueAsString(
                    java.util.Arrays.stream(args)
                            .filter(a -> !(a instanceof HttpServletRequest)
                                    && !(a instanceof org.springframework.web.multipart.MultipartFile))
                            .toList());
        } catch (Exception e) {
            return "[不可序列化参数]";
        }
    }
}