package com.demo.akh.controller;

import com.demo.akh.common.exception.BizException;
import com.demo.akh.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class Test {
    @GetMapping("/hello")
    public Result<Map<String, Object>> hello(@RequestParam String name){
        return Result.success(Map.of("msg", "hello, " + name, "jdk", System.getProperty("java.version")));
    }

    @GetMapping("/boom")
    public Result<Object> boom(){
        throw new BizException(4001, "我是一个业务异常，用来测试全局处理器");
    }
}
