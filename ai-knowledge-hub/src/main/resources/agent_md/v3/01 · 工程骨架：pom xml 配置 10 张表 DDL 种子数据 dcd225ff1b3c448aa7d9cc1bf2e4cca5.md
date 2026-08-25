# 01 · 工程骨架：pom.xml / 配置 / 10 张表 DDL / 种子数据

<aside>
📌

本页把工程“能启动”所需的东西全部给齐：依赖、配置类、通用类、DDL、种子数据。**照拄就能跑**，不依赖外部 MySQL（默认 H2 内存库 + MySQL 方言，换 MySQL 只需改 4 行配置）。

</aside>

## 1. pom.xml

依赖选型原则：**能用 JDK / Spring 自带的就不引三方包**。HTTP 客户端用 JDK 11+ 内置的 `java.net.http.HttpClient`（天然支持 SSE 流式读取），JSON 用 Spring Web 自带的 Jackson，表达式用 Spring 自带的 SpEL。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.6.13</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>simulation-agent</artifactId>
    <version>1.0.0</version>
    <name>simulation-agent</name>
    <description>Enterprise-grade simulation agent: NL -> Workflow -> MCP Servers</description>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Web + SSE -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- 参数校验 @Valid -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <!-- JdbcTemplate（故意不用 JPA：便于你直接看清 SQL，也方便平移到 MyBatis） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <!-- 健康检查 / 指标 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!-- 开发期内存库；生产换 MySQL 时把 scope 改成 test 即可 -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>mysql</groupId>
            <artifactId>mysql-connector-java</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

<aside>
💡

**为什么不用 JPA/MyBatis-Plus？** 不是因为不能用，而是为了让你学习时能**看到每一句 SQL**。你实际项目里把 `*Repository` 里的 `JdbcTemplate` 换成你现有的 Mapper 就行，其他层零改动。

</aside>

---

## 2. 启动类

```java
package com.example.simagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class SimAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimAgentApplication.class, args);
    }
}
```

---

## 3. 配置类

### 3.1 `config/LlmProperties.java`

```java
package com.example.simagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "agent.llm")
public class LlmProperties {

    /** true = 使用内置规则引擎（MockLlmClient），无需 API Key 即可跑通全链路 */
    private boolean mock = true;

    /** OpenAI 兼容的 base-url，末尾不带 / 。例：https://api.openai.com/v1 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey = "";

    private String model = "qwen-plus";

    private double temperature = 0.1D;

    private int maxTokens = 2048;

    /** 单次请求超时 */
    private int timeoutSeconds = 120;

    /** 429 / 5xx 自动重试次数 */
    private int maxRetries = 2;

    /** 是否用流式接口拉 token（开启后前端能看到逐字输出） */
    private boolean stream = true;

    /** 网关鉴权等额外头 */
    private Map<String, String> extraHeaders = new LinkedHashMap<>();
}
```

### 3.2 `config/AgentProperties.java`

```java
package com.example.simagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** ReAct 循环最大步数（防止大模型死循环烧 token） */
    private int maxSteps = 12;

    /** 单个工具执行超时 */
    private int toolTimeoutSeconds = 300;

    /** 送给大模型的历史消息条数上限（超出会裁剪，保留 system + 最新 N 条） */
    private int historyLimit = 40;

    /** SSE 连接超时毫秒，必须大于一次完整 workflow 的执行时长 */
    private long sseTimeoutMs = 30 * 60 * 1000L;

    /**
     * 强制人工确认的工具名单（HITL）。
     * 除了工具自身的 requiresConfirmation()，这里还提供一个运维可改的开关。
     */
    private List<String> confirmRequiredTools = Arrays.asList(
            "create_workflow_instance", "run_workflow_instance");

    private Workflow workflow = new Workflow();

    @Data
    public static class Workflow {
        /** 同一个实例内部，同层节点最大并行度 */
        private int parallelism = 4;
        /** 节点失败重试次数（不含首次） */
        private int nodeRetry = 1;
        /** 重试基础退避毫秒 */
        private long retryBackoffMs = 2000L;
        /** true = 任一节点失败就中止整个实例；false = 尽可能跑完无依赖分支 */
        private boolean failFast = true;
        /** 单节点（单次 MCP 调用）超时 */
        private int nodeTimeoutSeconds = 600;
    }
}
```

### 3.3 `config/McpProperties.java`

```java
package com.example.simagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 握手 + tools/list 超时 */
    private int startupTimeoutSeconds = 30;

    /** tools/call 默认超时 */
    private int callTimeoutSeconds = 600;

    /** 健康检查间隔 */
    private int healthCheckIntervalSeconds = 30;

    /** 启动时某个 MCP Server 起不来是否直接启动失败（生产建议 true，开发 false） */
    private boolean failFastOnStartup = false;

    /** 自动重连最大连续失败次数，超过后进入熔断（需手动 /api/mcp/reconnect） */
    private int maxReconnectAttempts = 5;

    private List<ServerConfig> servers = new ArrayList<>();

    @Data
    public static class ServerConfig {
        /** 唯一名，与 golden_template.mcp_server 对应 */
        private String name;
        private boolean enabled = true;
        /** 目前支持 stdio（预留 http-sse） */
        private String transport = "stdio";
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String workDir;
        private String description;
        /** 是否把该 server 的 tools 直接暂开给大模型（单步仿真场景有用；关闭则只能走 workflow） */
        private boolean exposeToLlm = true;
    }
}
```

### 3.4 `config/ThreadPoolConfig.java`

```java
package com.example.simagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    /** 跑 Agent 主循环（一个会话一个任务，长耗时、IO 密集） */
    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(8);
        e.setMaxPoolSize(32);
        e.setQueueCapacity(200);
        e.setKeepAliveSeconds(120);
        e.setThreadNamePrefix("agent-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(30);
        e.initialize();
        return e;
    }

    /** 跑 workflow 节点（同层并行）。与 agentExecutor 隔离，避免互相饿死 */
    @Bean("workflowExecutor")
    public ThreadPoolTaskExecutor workflowExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(8);
        e.setMaxPoolSize(64);
        e.setQueueCapacity(500);
        e.setThreadNamePrefix("wf-node-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.setWaitForTasksToCompleteOnShutdown(true);
        e.setAwaitTerminationSeconds(60);
        e.initialize();
        return e;
    }
}
```

<aside>
⚠️

**两个线程池必须隔离**。Agent 主循环会同步等 workflow 执行完，如果共用线程池，高并发时会出现「主循环占完线程、节点任务排不上队」的经典线程池死锁。

</aside>

### 3.5 `config/WebConfig.java`

```java
package com.example.simagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
```

---

## 4. 通用类

### 4.1 `common/R.java` + `ErrorCode` + `BizException`

```java
package com.example.simagent.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.MDC;

@Data
@AllArgsConstructor
public class R<T> {
    private int code;
    private String msg;
    private T data;
    private String traceId;

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data, MDC.get(TraceIdFilter.TRACE_ID));
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null, MDC.get(TraceIdFilter.TRACE_ID));
    }
}
```

```java
package com.example.simagent.common;

public interface ErrorCode {
    int PARAM_INVALID       = 40001;
    int TEMPLATE_NOT_FOUND  = 40401;
    int INSTANCE_NOT_FOUND  = 40402;
    int GOLDEN_NOT_FOUND    = 40403;
    int SESSION_NOT_FOUND   = 40404;
    int DAG_INVALID         = 42201;
    int MCP_UNAVAILABLE     = 50301;
    int MCP_CALL_FAILED     = 50302;
    int LLM_CALL_FAILED     = 50303;
    int AGENT_LOOP_OVERFLOW = 50304;
    int INTERNAL_ERROR      = 50000;
}
```

```java
package com.example.simagent.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(int code, String msg, Throwable cause) {
        super(msg, cause);
        this.code = code;
    }

    public static BizException of(int code, String fmt, Object... args) {
        return new BizException(code, String.format(fmt, args));
    }
}
```

### 4.2 `common/GlobalExceptionHandler.java`

```java
package com.example.simagent.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> onBiz(BizException e) {
        log.warn("[biz-error] code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> onValid(Exception e) {
        return R.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> onOther(Exception e) {
        log.error("[internal-error]", e);
        return R.fail(ErrorCode.INTERNAL_ERROR, "系统异常：" + e.getMessage());
    }
}
```

### 4.3 `common/JsonUtils.java`

```java
package com.example.simagent.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.Map;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法 JSON: " + json, e);
        }
    }

    /** 大模型返回的 arguments 偶尔会带 markdown 围栏或为空，这里做容错 */
    public static JsonNode readToolArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return obj();
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```$", "").trim();
        }
        try {
            JsonNode n = MAPPER.readTree(s);
            return n.isObject() ? n : obj();
        } catch (Exception e) {
            return obj();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static <T> T convert(JsonNode node, Class<T> clazz) {
        return MAPPER.convertValue(node, clazz);
    }

    public static String text(JsonNode node, String field, String def) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }
}
```

### 4.4 `common/TraceIdFilter.java`

```java
package com.example.simagent.common;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Order(1)
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        resp.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
```

---

## 5. `resources/application.yml`

```yaml
server:
  port: 8080
  servlet:
    encoding:
      charset: UTF-8
      force: true

spring:
  application:
    name: simulation-agent

  # ---------- 开发：H2 内存库（MySQL 方言）----------
  datasource:
    url: jdbc:h2:mem:simagent;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  # ---------- 生产：MySQL（把上面 4 行注掉，换成下面）----------
  #  datasource:
  #    url: jdbc:mysql://127.0.0.1:3306/simagent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
  #    driver-class-name: com.mysql.cj.jdbc.Driver
  #    username: root
  #    password: root

  sql:
    init:
      mode: always            # 生产改为 never，用 flyway/liquibase 管理
      schema-locations: classpath:db/schema.sql
      data-locations: classpath:db/data.sql
      continue-on-error: false

  h2:
    console:
      enabled: true           # http://localhost:8080/h2-console
      path: /h2-console

  mvc:
    async:
      request-timeout: 1800000   # 30min，SSE 长连接必须调大

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

# =========================================================
# Agent 配置
# =========================================================
agent:
  max-steps: 12
  tool-timeout-seconds: 300
  history-limit: 40
  sse-timeout-ms: 1800000
  confirm-required-tools:
    - create_workflow_instance
    - run_workflow_instance
  workflow:
    parallelism: 4
    node-retry: 1
    retry-backoff-ms: 2000
    fail-fast: true
    node-timeout-seconds: 600

  llm:
    # 默认 true：不需要任何 API Key 就能把全链路跑通（内置确定性规则引擎）
    mock: ${LLM_MOCK:true}
    base-url: ${LLM_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:qwen-plus}
    temperature: 0.1
    max-tokens: 2048
    timeout-seconds: 120
    max-retries: 2
    stream: true

# =========================================================
# MCP Server 插件注册：新增仿真类型只需在这里加一段 + 库里加一条 golden_template
# =========================================================
mcp:
  startup-timeout-seconds: 30
  call-timeout-seconds: 600
  health-check-interval-seconds: 30
  fail-fast-on-startup: false
  max-reconnect-attempts: 5
  servers:
    - name: coventor
      description: "Coventor MEMS+ 结构/多物理场仿真"
      enabled: true
      transport: stdio
      command: ${PYTHON_BIN:python3}
      args: [ "-u", "mcp-servers/python/coventor_server.py" ]
      env:
        SIM_MOCK: "true"
        PYTHONIOENCODING: "utf-8"
      expose-to-llm: true

    - name: slitho
      description: "S-Litho 光刻仿真"
      enabled: true
      transport: stdio
      command: ${PYTHON_BIN:python3}
      args: [ "-u", "mcp-servers/python/slitho_server.py" ]
      env:
        SIM_MOCK: "true"
        PYTHONIOENCODING: "utf-8"
      expose-to-llm: true

    - name: report
      description: "仿真后处理与报告生成"
      enabled: true
      transport: stdio
      command: ${PYTHON_BIN:python3}
      args: [ "-u", "mcp-servers/python/report_server.py" ]
      env:
        PYTHONIOENCODING: "utf-8"
      expose-to-llm: false      # 只允许在 workflow 中被调用，不直接暂给大模型

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n"
  level:
    com.example.simagent: DEBUG
    com.example.simagent.mcp: DEBUG
```

---

## 6. `resources/db/schema.sql`（10 张表）

<aside>
🔍

你现有的 6 张表只需关注 **默认粗体注释标记的新增字段**；后 4 张是 Agent 侧新增。

</aside>

```sql
-- =========================================================
-- 1) Golden Template：仿真任务模板（参数已预配）
--    ★ mcp_server / mcp_tool = 「节点 -> MCP Server」路由表，本方案新增
-- =========================================================
DROP TABLE IF EXISTS golden_template;
CREATE TABLE golden_template (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL COMMENT '模板唯一编码',
    name            VARCHAR(128) NOT NULL COMMENT '模板名',
    simulation_type VARCHAR(32)  NOT NULL COMMENT 'COVENTOR / SLITHO / REPORT / MESH ...',
    mcp_server      VARCHAR(64)  NOT NULL COMMENT '★ 对应 mcp.servers[].name',
    mcp_tool        VARCHAR(64)  NOT NULL COMMENT '★ 对应 MCP Server 里的 tool 名',
    param_schema    TEXT COMMENT '参数 JSON Schema（给大模型看的）',
    default_params  TEXT COMMENT '默认参数 JSON（“大多数不用自己输”就靠这个）',
    description     VARCHAR(512),
    est_minutes     INT          DEFAULT 5 COMMENT '预估耗时，用于给用户报预期',
    version         INT          DEFAULT 1,
    enabled         TINYINT      DEFAULT 1,
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_golden_code ON golden_template (code);
CREATE INDEX idx_golden_type ON golden_template (simulation_type);

-- =========================================================
-- 2) Workflow 模板三张表
-- =========================================================
DROP TABLE IF EXISTS wf_template;
CREATE TABLE wf_template (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(1024),
    biz_domain   VARCHAR(64)  COMMENT 'MEMS / LITHO / RF ...',
    tags         VARCHAR(256) COMMENT '逗号分隔，供模糊检索',
    status       VARCHAR(16)  DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / ARCHIVED',
    version      INT          DEFAULT 1,
    created_by   VARCHAR(64)  DEFAULT 'system' COMMENT 'system / agent / 用户ID',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_wft_code ON wf_template (code);
CREATE INDEX idx_wft_name ON wf_template (name);

DROP TABLE IF EXISTS wf_node_template;
CREATE TABLE wf_node_template (
    id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
    wf_template_id     BIGINT      NOT NULL,
    node_key           VARCHAR(64) NOT NULL COMMENT '模板内唯一，edge 靠它引用',
    node_name          VARCHAR(128),
    golden_template_id BIGINT      NOT NULL COMMENT '引用哪个 Golden Template',
    param_overrides    TEXT COMMENT '在默认参数上的覆盖项 JSON，支持 ${xxx} 占位符',
    retry              INT         DEFAULT 1,
    timeout_sec        INT         DEFAULT 600,
    sort_no            INT         DEFAULT 0,
    create_time        DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_wfnt_key ON wf_node_template (wf_template_id, node_key);

DROP TABLE IF EXISTS wf_edge_template;
CREATE TABLE wf_edge_template (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    wf_template_id  BIGINT      NOT NULL,
    source_node_key VARCHAR(64) NOT NULL,
    target_node_key VARCHAR(64) NOT NULL,
    condition_expr  VARCHAR(512) COMMENT 'SpEL，为空=无条件。例：#outputs[''mesh''][''quality''] > 0.8',
    create_time     DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_wfet_tpl ON wf_edge_template (wf_template_id);

-- =========================================================
-- 3) Workflow 实例三张表
-- =========================================================
DROP TABLE IF EXISTS wf_instance;
CREATE TABLE wf_instance (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    template_id   BIGINT       NOT NULL,
    template_code VARCHAR(64),
    name          VARCHAR(128),
    status        VARCHAR(16)  DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELED',
    trigger_type  VARCHAR(16)  DEFAULT 'AGENT'  COMMENT 'AGENT / MANUAL / API / SCHEDULE',
    session_id    VARCHAR(64)  COMMENT '★ 哪个 Agent 会话触发的，用于事件回推',
    idempotent_key VARCHAR(128) COMMENT '★ 幂等键，防止大模型重复调用创建多个实例',
    input_params  TEXT COMMENT '实例级入参 JSON，可被 ${input.xxx} 引用',
    output_result TEXT,
    error_msg     VARCHAR(2048),
    start_time    DATETIME,
    end_time      DATETIME,
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_wfi_tpl ON wf_instance (template_id);
CREATE INDEX idx_wfi_session ON wf_instance (session_id);
CREATE UNIQUE INDEX uk_wfi_idem ON wf_instance (idempotent_key);

DROP TABLE IF EXISTS wf_node_instance;
CREATE TABLE wf_node_instance (
    id                 BIGINT      AUTO_INCREMENT PRIMARY KEY,
    wf_instance_id     BIGINT      NOT NULL,
    node_key           VARCHAR(64) NOT NULL,
    node_name          VARCHAR(128),
    golden_template_id BIGINT      NOT NULL,
    simulation_type    VARCHAR(32) NOT NULL COMMENT '快照，防模板后续被改',
    mcp_server         VARCHAR(64) NOT NULL COMMENT '★ 快照路由',
    mcp_tool           VARCHAR(64) NOT NULL COMMENT '★ 快照路由',
    params             TEXT COMMENT '最终参数（默认+覆盖+入参+上游注入合并后）',
    status             VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    attempt            INT         DEFAULT 0,
    output_result      TEXT,
    error_msg          VARCHAR(2048),
    cost_ms            BIGINT      DEFAULT 0,
    start_time         DATETIME,
    end_time           DATETIME,
    create_time        DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_wfni_key ON wf_node_instance (wf_instance_id, node_key);

DROP TABLE IF EXISTS wf_edge_instance;
CREATE TABLE wf_edge_instance (
    id              BIGINT      AUTO_INCREMENT PRIMARY KEY,
    wf_instance_id  BIGINT      NOT NULL,
    source_node_key VARCHAR(64) NOT NULL,
    target_node_key VARCHAR(64) NOT NULL,
    condition_expr  VARCHAR(512),
    passed          TINYINT     DEFAULT NULL COMMENT '条件边实际是否放行，便于回溯',
    create_time     DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_wfei_inst ON wf_edge_instance (wf_instance_id);

-- =========================================================
-- 4) Agent 侧 4 张表
-- =========================================================
DROP TABLE IF EXISTS agent_session;
CREATE TABLE agent_session (
    id             VARCHAR(64)  PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL,
    title          VARCHAR(256),
    status         VARCHAR(24)  DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING/WAITING_CONFIRM/CLOSED',
    pending_action TEXT COMMENT '★ HITL：被挂起的工具调用快照 JSON',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sess_user ON agent_session (user_id);

DROP TABLE IF EXISTS agent_message;
CREATE TABLE agent_message (
    id           BIGINT      AUTO_INCREMENT PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL,
    seq          INT         NOT NULL COMMENT '会话内递增，保证回放顺序',
    role         VARCHAR(16) NOT NULL COMMENT 'system/user/assistant/tool',
    content      TEXT,
    tool_calls   TEXT COMMENT 'assistant 消息的 tool_calls 原文',
    tool_call_id VARCHAR(64) COMMENT 'tool 消息对应的调用 id',
    tool_name    VARCHAR(64),
    create_time  DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_msg_seq ON agent_message (session_id, seq);

DROP TABLE IF EXISTS agent_tool_audit;
CREATE TABLE agent_tool_audit (
    id             BIGINT      AUTO_INCREMENT PRIMARY KEY,
    session_id     VARCHAR(64) NOT NULL,
    trace_id       VARCHAR(64),
    tool_name      VARCHAR(64) NOT NULL,
    tool_type      VARCHAR(16) COMMENT 'BUILTIN / MCP',
    arguments      TEXT,
    success        TINYINT,
    result_summary VARCHAR(2048),
    error_msg      VARCHAR(1024),
    cost_ms        BIGINT,
    confirmed_by   VARCHAR(64) COMMENT '需确认的工具，记录确认人',
    create_time    DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_sess ON agent_tool_audit (session_id);

DROP TABLE IF EXISTS agent_llm_call_log;
CREATE TABLE agent_llm_call_log (
    id            BIGINT      AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64),
    trace_id      VARCHAR(64),
    step          INT,
    model         VARCHAR(64),
    prompt_tokens INT,
    output_tokens INT,
    finish_reason VARCHAR(32),
    cost_ms       BIGINT,
    error_msg     VARCHAR(1024),
    create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_llmlog_sess ON agent_llm_call_log (session_id);
```

---

## 7. `resources/db/data.sql`（种子数据）

故意只预置一个**已发布的简单 workflow**，这样你可以分别验证两条分支：

- 输入“跑个 Coventor 结构仿真” → 命中现成模板 → 直接建实例
- 输入“结构+光刻+报告的完整仿真” → 未命中 → Agent 新建模板

```sql
-- ---------- Golden Templates（4 个，对应 3 个 MCP Server）----------
INSERT INTO golden_template
(code, name, simulation_type, mcp_server, mcp_tool, param_schema, default_params, description, est_minutes)
VALUES
('GT_MESH_STD', '标准网格划分', 'MESH', 'coventor', 'run_mesh_generation',
 '{"type":"object","properties":{"model_name":{"type":"string","description":"模型/器件名称"},"mesh_size":{"type":"number","description":"网格尺寸(um)"},"element_type":{"type":"string","enum":["TET","HEX"]}},"required":["model_name"]}',
 '{"mesh_size":2.0,"element_type":"TET","mock_seconds":1}',
 '仿真前处理：对器件模型做网格划分，输出 mesh 文件与网格质量指标', 2),

('GT_COVENTOR_MODAL', 'Coventor 模态/结构仿真', 'COVENTOR', 'coventor', 'run_coventor_simulation',
 '{"type":"object","properties":{"model_name":{"type":"string"},"mesh_file":{"type":"string","description":"上游网格文件路径"},"solver":{"type":"string","enum":["MODAL","STATIC","HARMONIC"]},"temperature":{"type":"number"},"pressure":{"type":"number"}},"required":["model_name"]}',
 '{"solver":"MODAL","temperature":25,"pressure":101325,"mock_seconds":2}',
 'MEMS 器件结构/多物理场仿真，输出谐振频率、应力、位移等', 8),

('GT_SLITHO_OPC', 'S-Litho 光刻仿真', 'SLITHO', 'slitho', 'run_slitho_simulation',
 '{"type":"object","properties":{"mask_file":{"type":"string"},"wavelength_nm":{"type":"number"},"numerical_aperture":{"type":"number"},"dose":{"type":"number"},"focus_nm":{"type":"number"},"resist_model":{"type":"string"}},"required":["mask_file"]}',
 '{"wavelength_nm":193,"numerical_aperture":1.35,"dose":30,"focus_nm":0,"resist_model":"CM1","mock_seconds":2}',
 '光刻成像与光刋胶显影仿真，输出 CD、EPE、工艺窗口', 10),

('GT_REPORT_SUMMARY', '仿真结果汇总报告', 'REPORT', 'report', 'generate_report',
 '{"type":"object","properties":{"title":{"type":"string"},"sources":{"type":"array","items":{"type":"object"},"description":"上游节点输出列表"},"format":{"type":"string","enum":["MD","HTML","PDF"]}},"required":["title"]}',
 '{"format":"MD","mock_seconds":1}',
 '把上游所有仿真节点的结果聚合成一份报告', 1);

-- ---------- 预置一个已发布 workflow：网格 -> 结构仿真 ----------
INSERT INTO wf_template (code, name, description, biz_domain, tags, status, created_by)
VALUES ('WFT_COVENTOR_BASIC', 'Coventor 基础结构仿真', '网格划分 -> Coventor 模态仿真，适用于单一器件快速评估',
        'MEMS', 'coventor,结构仿真,模态,mems,网格', 'PUBLISHED', 'system');

INSERT INTO wf_node_template (wf_template_id, node_key, node_name, golden_template_id, param_overrides, retry, timeout_sec, sort_no)
VALUES
(1, 'mesh',     '网格划分',        1, '{}', 1, 300, 0),
(1, 'coventor', 'Coventor 模态仿真', 2, '{"mesh_file":"${mesh.output.mesh_file}"}', 1, 600, 1);

INSERT INTO wf_edge_template (wf_template_id, source_node_key, target_node_key, condition_expr)
VALUES (1, 'mesh', 'coventor', NULL);
```

<aside>
🧩

注意 `param_overrides` 里的 `${mesh.output.mesh_file}` —— 这就是「上游输出注入下游参数」的声明式写法，由 `ParamResolver` 在运行时解析（见第 07 页）。这是串联仿真能真正跑通的关键。

</aside>

---

## 8. 自检清单

- [ ]  `mvn -q spring-boot:run` 能启动，无报错
- [ ]  `http://localhost:8080/h2-console` 能看到 10 张表，`golden_template` 有 4 行
- [ ]  `http://localhost:8080/actuator/health` 返回 UP
- [ ]  日志里能看到 `[mcp] server 'coventor' UP, tools=[...]`（需先完成第 02/03 页）