package com.demo.akh;

import java.util.*;
import java.util.stream.*;
//在新分支上新增注释
public class JavaWarmUp {

    record User(Long id, String name, Integer age, String dept) {}

    public static void main(String[] args) {
        List<User> users = List.of(
                new User(1L, "张三", 25, "研发"),
                new User(2L, "李四", 31, "研发"),
                new User(3L, "王五", 28, "产品"),
                new User(4L, "赵六", 35, "产品")
        );

        // 1. 过滤 + 映射 + 收集（最高频）
        List<String> names = users.stream()
                .filter(u -> u.age() > 27)
                .map(User::name)
                .toList();
        System.out.println(names);

        // 2. 转 Map（写业务时天天用：把列表转成 id -> 对象）
        Map<Long, User> idMap = users.stream()
                .collect(Collectors.toMap(User::id, u -> u));
        System.out.println(idMap.get(2L));

        // 3. 分组（统计类需求必备）
        Map<String, List<User>> byDept = users.stream()
                .collect(Collectors.groupingBy(User::dept));
        System.out.println(byDept);

        // 4. 分组计数 / 求平均
        Map<String, Long> countByDept = users.stream()
                .collect(Collectors.groupingBy(User::dept, Collectors.counting()));
        System.out.println(countByDept);

        // 5. 排序（多字段）
        List<User> sorted = users.stream()
                .sorted(Comparator.comparing(User::dept).thenComparing(User::age, Comparator.reverseOrder()))
                .toList();
        sorted.forEach(System.out::println);

        // 6. 拼接字符串
        String joined = users.stream().map(User::name).collect(Collectors.joining(", ", "[", "]"));
        System.out.println(joined);

        // 7. Optional（避免 NPE 的正确姿势）
        String first = users.stream()
                .filter(u -> u.age() > 100)
                .findFirst()
                .map(User::name)
                .orElse("没找到");
        System.out.println(first);

        // 8. 异常处理 + try-with-resources
        try {
            int r = 10 / 0;
        } catch (ArithmeticException e) {
            System.out.println("捕获异常: " + e.getMessage());
        } finally {
            System.out.println("finally 总会执行");
        }

        // 9. 泛型方法
        System.out.println(firstOf(List.of("a", "b")));
        System.out.println(firstOf(List.of(1, 2, 3)));

        // 10. 函数式接口（Spring 源码里到处都是）
        java.util.function.Function<String, Integer> len = String::length;
        java.util.function.Predicate<String> notBlank = s -> s != null && !s.isBlank();
        java.util.function.Supplier<String> sup = () -> "延迟创建";
        System.out.println(len.apply("hello") + " " + notBlank.test(" ") + " " + sup.get());

        // 11. 文本块（Java 15+，写 Prompt 特别有用）
        String prompt = """
                你是一个专业助手。
                请基于以下资料回答问题：
                {context}
                """;
        System.out.println(prompt);
    }

    static <T> T firstOf(List<T> list) {
        return list.isEmpty() ? null : list.get(0);
    }
}