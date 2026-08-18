package com.demo.akh.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    /** 时间窗口（秒） */
    int window() default 60;
    /** 窗口内最大请求次数 */
    int limit() default 10;
    /** 限流提示 */
    String message() default "请求过于频繁，请稍后再试";
}