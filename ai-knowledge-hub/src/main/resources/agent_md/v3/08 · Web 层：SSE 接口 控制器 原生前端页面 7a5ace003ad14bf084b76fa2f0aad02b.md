# 08 · Web 层：SSE 接口 / 控制器 / 原生前端页面

<aside>
🎯

这一页把前面所有能力暴露成 HTTP 接口，并给一个**零构建工具链的单文件前端**（原生 `EventSource`）。启动应用后直接访问 `http://localhost:8080/` 就能聊。

</aside>

## 1. 接口清单

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/agent/chat/stream` | **主入口**：发消息，SSE 流式返回全过程 |
| GET | `/api/agent/confirm/stream` | 确认/拒绝后继续执行，仍是 SSE |
| GET | `/api/agent/sessions/{sessionId}/messages` | 历史消息（刷新页面后恢复） |
| GET | `/api/workflow/templates` | 工作流模版列表 |
| GET | `/api/workflow/templates/{code}/graph` | 模版 DAG 结构 |
| GET | `/api/workflow/instances/{id}` | 实例详情（节点/边/输出） |
| POST | `/api/workflow/templates/{code}/publish` | DRAFT → PUBLISHED |
| GET | `/api/mcp/servers` | 插件进程健康状态 |
| POST | `/api/mcp/servers/{name}/reconnect` | 手动重连插件 |

<aside>
❗

**为什么聊天接口是 GET 而不是 POST？** 浏览器原生 `EventSource` **只支持 GET**，不能带请求体。三种选择：① GET + query 参数（本方案，最简单）；② POST 先存消息再 GET 开流（两次往返，适合超长消息）；③ 换 `fetch` + `ReadableStream` 自己解析 SSE（可以 POST，但要自己处理断包）。生产环境建议②或③。

</aside>

---

## 2. 基础设施：traceId 与异步线程池

```java
package com.example.simagent.config;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求打 traceId，全链日志（LLM 调用 / 工具审计 / 节点执行）都靠它串起来。
 * 注意 Spring Boot 2.6 是 javax.servlet（不是 jakarta）。
 */
@Order(1)
@Component
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID = "traceId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
```

```java
package com.example.simagent.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /**
     * Agent 主循环线程池。
     *
     * 关键点：SSE 接口不能占用 Tomcat 工作线程跑几分钟的 Agent 循环，
     * 否则并发上去一点 Tomcat 线程池（默认 200）就被拖死了。
     * 所以 controller 立即返回 SseEmitter，真正的活活交给这个池。
     */
    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(32);
        ex.setQueueCapacity(200);
        ex.setKeepAliveSeconds(120);
        ex.setThreadNamePrefix("agent-loop-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // MDC 透传：否则异步线程里 traceId 丢失
        ex.setTaskDecorator(runnable -> {
            Map<String, String> parent = MDC.getCopyOfContextMap();
            return () -> {
                if (parent != null) {
                    MDC.setContextMap(parent);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        ex.initialize();
        return ex;
    }
}
```

---

## 3. DTO

```java
package com.example.simagent.controller.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String sessionId;
    private String userId;
    private String message;
}
```

```java
package com.example.simagent.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
public class WorkflowInstanceVO {
    private Long id;
    private String templateCode;
    private String name;
    private String status;
    private Long costMs;
    private String errorMsg;
    private Map<String, Object> inputParams;
    private List<Map<String, Object>> nodes;
    private List<Map<String, Object>> edges;
}
```

---

## 4. `AgentChatController` —— 整个系统的门面

```java
package com.example.simagent.controller;

import com.example.simagent.agent.AgentOrchestrator;
import com.example.simagent.agent.event.AgentEvent;
import com.example.simagent.agent.event.SseEventSink;
import com.example.simagent.agent.event.SseSessionRegistry;
import com.example.simagent.agent.memory.AgentMessage;
import com.example.simagent.agent.memory.ConversationStore;
import com.example.simagent.config.AgentProperties;
import com.example.simagent.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentChatController {

    private final AgentOrchestrator orchestrator;
    private final SseSessionRegistry sseRegistry;
    private final ConversationStore store;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties props;

    public AgentChatController(AgentOrchestrator orchestrator,
                              SseSessionRegistry sseRegistry,
                              ConversationStore store,
                              ThreadPoolTaskExecutor agentExecutor,
                              AgentProperties props) {
        this.orchestrator = orchestrator;
        this.sseRegistry = sseRegistry;
        this.store = store;
        this.agentExecutor = agentExecutor;
        this.props = props;
    }

    /**
     * 发送一条消息，SSE 返回整个 ReAct 过程。
     *
     * curl -N "http://localhost:8080/api/agent/chat/stream?message=帮我跑一个 coventor 模态仿真"
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String message,
                                 @RequestParam(required = false) String sessionId,
                                 @RequestParam(required = false, defaultValue = "anonymous")
                                 String userId) {

        String sid = (sessionId == null || sessionId.isBlank())
                ? "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : sessionId;

        SseEmitter emitter = new SseEmitter(props.getSseTimeoutMs());
        SseEventSink sink = new SseEventSink(sid, emitter);
        sseRegistry.register(sid, sink);

        // 连接结束/超时/出错都要注销，否则 registry 泄漏
        emitter.onCompletion(() -> {
            sseRegistry.unregister(sid);
            log.info("[sse] 连接完成 session={} 当前活跃={}", sid, sseRegistry.activeCount());
        });
        emitter.onTimeout(() -> {
            log.warn("[sse] 连接超时 session={}", sid);
            sseRegistry.unregister(sid);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.warn("[sse] 连接异常 session={}: {}", sid, e.getMessage());
            sseRegistry.unregister(sid);
        });

        // 立即握手，前端拿到 sessionId
        sink.emit(AgentEvent.sessionStart(sid));

        agentExecutor.execute(() -> {
            try {
                orchestrator.handleUserMessage(sid, userId, message, sink);
            } catch (Exception e) {
                log.error("[sse] Agent 循环异常 session={}", sid, e);
                sink.emit(AgentEvent.error(sid, "系统异常：" + e.getMessage()));
                sink.emit(AgentEvent.done(sid));
            } finally {
                sink.close();
            }
        });

        return emitter;
    }

    /**
     * 人工确认后继续。前端会**重新开一条 SSE**（上一条已在 confirm_required 后结束）。
     */
    @GetMapping(value = "/confirm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter confirmStream(@RequestParam String sessionId,
                                    @RequestParam boolean approved,
                                    @RequestParam(required = false, defaultValue = "anonymous")
                                    String userId) {

        SseEmitter emitter = new SseEmitter(props.getSseTimeoutMs());
        SseEventSink sink = new SseEventSink(sessionId, emitter);
        sseRegistry.register(sessionId, sink);

        emitter.onCompletion(() -> sseRegistry.unregister(sessionId));
        emitter.onTimeout(() -> {
            sseRegistry.unregister(sessionId);
            emitter.complete();
        });
        emitter.onError(e -> sseRegistry.unregister(sessionId));

        sink.emit(AgentEvent.sessionStart(sessionId));

        agentExecutor.execute(() -> {
            try {
                orchestrator.resumeAfterConfirm(sessionId, userId, approved, sink);
            } catch (Exception e) {
                log.error("[sse] 确认恢复异常 session={}", sessionId, e);
                sink.emit(AgentEvent.error(sessionId, "恢复执行失败：" + e.getMessage()));
                sink.emit(AgentEvent.done(sessionId));
            } finally {
                sink.close();
            }
        });

        return emitter;
    }

    /** 刷新页面后恢复历史 */
    @GetMapping("/sessions/{sessionId}/messages")
    public R<List<AgentMessage>> history(@PathVariable String sessionId) {
        return R.ok(store.loadHistory(sessionId, props.getHistoryLimit()));
    }

    @GetMapping("/health")
    public R<Object> health() {
        return R.ok(java.util.Map.of(
                "activeSseSessions", sseRegistry.activeCount(),
                "llmMock", props.getLlm().isMock(),
                "maxSteps", props.getMaxSteps()));
    }
}
```

<aside>
🔌

这里调的 `orchestrator.handleUserMessage(sessionId, userId, message, sink)` 与 `resumeAfterConfirm(sessionId, userId, approved, sink)` 就是 05 页 `AgentOrchestrator` 的两个公开入口。如果你实现时参数顺序不同，**以 05 页的方法签名为准**，把这里对齐即可。

</aside>

---

## 5. `WorkflowController`

```java
package com.example.simagent.controller;

import com.example.simagent.common.JsonUtils;
import com.example.simagent.common.R;
import com.example.simagent.controller.dto.WorkflowInstanceVO;
import com.example.simagent.workflow.domain.*;
import com.example.simagent.workflow.service.WorkflowInstanceService;
import com.example.simagent.workflow.service.WorkflowTemplateService;
import com.example.simagent.workflow.repository.GoldenTemplateRepository;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflow")
@CrossOrigin
public class WorkflowController {

    private final WorkflowTemplateService templateService;
    private final WorkflowInstanceService instanceService;
    private final GoldenTemplateRepository goldenRepo;

    public WorkflowController(WorkflowTemplateService templateService,
                             WorkflowInstanceService instanceService,
                             GoldenTemplateRepository goldenRepo) {
        this.templateService = templateService;
        this.instanceService = instanceService;
        this.goldenRepo = goldenRepo;
    }

    @GetMapping("/golden-templates")
    public R<List<GoldenTemplate>> goldenTemplates() {
        return R.ok(goldenRepo.findAllEnabled());
    }

    @GetMapping("/templates")
    public R<List<WorkflowTemplate>> templates(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(templateService.search(keyword, limit));
    }

    @GetMapping("/templates/{code}/graph")
    public R<Map<String, Object>> graph(@PathVariable String code) {
        WorkflowGraph g = templateService.loadGraph(code);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeTemplate n : g.getNodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeKey", n.getNodeKey());
            m.put("name", n.getName());
            m.put("simType", n.getSimType());
            m.put("goldenTemplateCode", n.getGoldenTemplateCode());
            m.put("mcpServer", n.getMcpServer());
            m.put("mcpTool", n.getMcpTool());
            m.put("paramOverrides", JsonUtils.tryReadTree(n.getParamOverrides()));
            nodes.add(m);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (EdgeTemplate e : g.getEdges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", e.getFromNodeKey());
            m.put("to", e.getToNodeKey());
            m.put("conditionExpr", e.getConditionExpr());
            edges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template", g.getTemplate());
        out.put("nodes", nodes);
        out.put("edges", edges);
        return R.ok(out);
    }

    @PostMapping("/templates/{code}/publish")
    public R<String> publish(@PathVariable String code) {
        templateService.publish(code);
        return R.ok("published");
    }

    @GetMapping("/instances/{id}")
    public R<WorkflowInstanceVO> instance(@PathVariable long id) {
        WorkflowInstance w = instanceService.requireById(id);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeInstance n : instanceService.nodesOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeKey", n.getNodeKey());
            m.put("name", n.getName());
            m.put("simType", n.getSimType());
            m.put("mcpServer", n.getMcpServer());
            m.put("mcpTool", n.getMcpTool());
            m.put("status", n.getStatus());
            m.put("attempt", n.getAttempt());
            m.put("costMs", n.getCostMs());
            m.put("params", JsonUtils.tryReadTree(n.getParams()));
            m.put("output", JsonUtils.tryReadTree(n.getOutput()));
            m.put("errorMsg", n.getErrorMsg());
            nodes.add(m);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (EdgeInstance e : instanceService.edgesOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", e.getFromNodeKey());
            m.put("to", e.getToNodeKey());
            m.put("conditionExpr", e.getConditionExpr());
            m.put("passed", e.getPassed());
            edges.add(m);
        }

        return R.ok(new WorkflowInstanceVO(w.getId(), w.getTemplateCode(), w.getName(),
                w.getStatus(), w.getCostMs(), w.getErrorMsg(),
                JsonUtils.toMap(JsonUtils.tryReadTree(w.getInputParams())), nodes, edges));
    }
}
```

---

## 6. `McpAdminController` —— 插件运维面板

```java
package com.example.simagent.controller;

import com.example.simagent.common.R;
import com.example.simagent.mcp.McpServerHandle;
import com.example.simagent.mcp.McpServerRegistry;
import com.example.simagent.mcp.protocol.McpTool;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维接口：看插件活不活、暴了哪些工具、挂了一键重拉。
 * 生产环境请加权限控制（它能重启子进程）。
 */
@RestController
@RequestMapping("/api/mcp")
@CrossOrigin
public class McpAdminController {

    private final McpServerRegistry registry;

    public McpAdminController(McpServerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/servers")
    public R<List<Map<String, Object>>> servers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerHandle h : registry.handles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", h.getName());
            m.put("description", h.getDescription());
            m.put("alive", h.isAlive());
            m.put("exposeToLlm", h.isExposeToLlm());
            m.put("reconnectAttempts", h.getReconnectAttempts());
            List<Map<String, Object>> tools = new ArrayList<>();
            for (McpTool t : h.getTools()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.getName());
                tm.put("description", t.getDescription());
                tools.add(tm);
            }
            m.put("tools", tools);
            out.add(m);
        }
        return R.ok(out);
    }

    @PostMapping("/servers/{name}/reconnect")
    public R<String> reconnect(@PathVariable String name) {
        registry.manualReconnect(name);
        return R.ok("reconnect triggered: " + name);
    }
}
```

---

## 7. 全局异常处理

```java
package com.example.simagent.controller;

import com.example.simagent.common.BizException;
import com.example.simagent.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> biz(BizException e) {
        log.warn("[api] 业务异常: {}", e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> illegal(IllegalArgumentException e) {
        return R.fail("INVALID_PARAM", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> other(Exception e) {
        log.error("[api] 未预期异常", e);
        return R.fail("INTERNAL_ERROR", "系统异常，请查看服务端日志");
    }
}
```

<aside>
⚠️

`@RestControllerAdvice` **拦不到 SSE 开流之后的异常**（响应已经开始写）。所以 SSE 里的异常必须在异步任务里自己 try/catch，转成 `error` 事件发给前端——上面 controller 就是这么写的。

</aside>

---

## 8. 前端：`src/main/resources/static/index.html`

零构建工具链、单文件、原生 `EventSource`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>仿真 Agent 控制台</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; font-family: -apple-system, "Segoe UI", "PingFang SC", sans-serif;
         background:#0f1117; color:#e6e6e6; height:100vh; display:flex; }
  #left { flex:1; display:flex; flex-direction:column; min-width:0; }
  #right { width:360px; border-left:1px solid #232733; padding:12px; overflow-y:auto; background:#12141c; }
  header { padding:12px 16px; border-bottom:1px solid #232733; display:flex; align-items:center; gap:12px; }
  header h1 { font-size:15px; margin:0; font-weight:600; }
  .dot { width:8px; height:8px; border-radius:50%; background:#3ddc84; display:inline-block; }
  #chat { flex:1; overflow-y:auto; padding:16px; }
  .msg { margin-bottom:14px; max-width:78%; }
  .msg.user { margin-left:auto; }
  .bubble { padding:10px 13px; border-radius:10px; white-space:pre-wrap; line-height:1.6; font-size:14px; }
  .user .bubble { background:#2c5cff; color:#fff; }
  .ai .bubble { background:#1b1f2a; border:1px solid #262b38; }
  .think { color:#8b93a7; font-size:12.5px; font-style:italic; margin:6px 0; }
  .tool { background:#161a24; border:1px solid #262b38; border-left:3px solid #ffb020;
          border-radius:6px; padding:8px 10px; margin:6px 0; font-size:12.5px; }
  .tool.ok { border-left-color:#3ddc84; }
  .tool.err { border-left-color:#ff5c5c; }
  .tool pre { margin:6px 0 0; white-space:pre-wrap; word-break:break-all;
              color:#9aa4bd; font-size:11.5px; max-height:180px; overflow:auto; }
  .confirm { background:#1e1a10; border:1px solid #6b5320; border-radius:8px; padding:12px; margin:8px 0; }
  .confirm button { margin-right:8px; margin-top:8px; padding:6px 16px; border:none;
                    border-radius:6px; cursor:pointer; font-size:13px; }
  .btn-ok { background:#3ddc84; color:#04210f; font-weight:600; }
  .btn-no { background:#333a4a; color:#ddd; }
  #inputbar { display:flex; gap:8px; padding:12px; border-top:1px solid #232733; }
  #q { flex:1; padding:11px 13px; border-radius:8px; border:1px solid #2b3040;
       background:#161a24; color:#eee; font-size:14px; }
  #send { padding:11px 22px; border:none; border-radius:8px; background:#2c5cff;
          color:#fff; cursor:pointer; font-size:14px; }
  #send:disabled { background:#33384a; cursor:not-allowed; }
  .node { border:1px solid #262b38; border-radius:8px; padding:9px 11px; margin-bottom:8px; background:#161a24; }
  .node .k { font-weight:600; font-size:13px; }
  .node .s { font-size:11px; padding:2px 7px; border-radius:10px; margin-left:6px; }
  .PENDING { background:#333a4a; color:#aab; }
  .RUNNING { background:#2c5cff; color:#fff; animation:pulse 1.2s infinite; }
  .SUCCESS { background:#3ddc84; color:#04210f; }
  .FAILED  { background:#ff5c5c; color:#fff; }
  .SKIPPED { background:#6b5320; color:#ffd89b; }
  @keyframes pulse { 50% { opacity:.55; } }
  .hint { color:#6f7789; font-size:12px; }
  h3 { font-size:13px; color:#8b93a7; margin:4px 0 10px; }
</style>
</head>
<body>
<div id="left">
  <header>
    <span class="dot"></span>
    <h1>仿真 Agent 控制台</h1>
    <span class="hint" id="sid">未连接</span>
  </header>
  <div id="chat">
    <div class="msg ai"><div class="bubble">你好！我可以帮你编排并执行仿真任务。试试：
• 帮我跑一个 coventor 模态仿真
• 先生成网格，再并行跑 coventor 和 slitho，最后出报告
• 看看平台上有哪些仿真能力</div></div>
  </div>
  <div id="inputbar">
    <input id="q" placeholder="用自然语言描述你要做的仿真……" autocomplete="off">
    <button id="send">发送</button>
  </div>
</div>
<div id="right">
  <h3>执行时间线</h3>
  <div id="timeline"><div class="hint">还没有节点执行记录</div></div>
</div>

<script>
const chat = document.getElementById('chat');
const timeline = document.getElementById('timeline');
const input = document.getElementById('q');
const sendBtn = document.getElementById('send');
const sidLabel = document.getElementById('sid');

let sessionId = localStorage.getItem('sim_agent_sid') || '';
let es = null;
let aiBubble = null;          // 当前正在流式拼接的回答气泡
const nodeEls = {};           // nodeKey -> DOM

function scroll() { chat.scrollTop = chat.scrollHeight; }

function addUser(text) {
  chat.insertAdjacentHTML('beforeend',
    `<div class="msg user"><div class="bubble"></div></div>`);
  chat.lastElementChild.querySelector('.bubble').textContent = text;
  scroll();
}

function ensureAiBubble() {
  if (!aiBubble) {
    chat.insertAdjacentHTML('beforeend',
      `<div class="msg ai"><div class="bubble"></div></div>`);
    aiBubble = chat.lastElementChild.querySelector('.bubble');
  }
  return aiBubble;
}

function addBlock(html) {
  chat.insertAdjacentHTML('beforeend', `<div class="msg ai" style="max-width:92%">${html}</div>`);
  scroll();
  return chat.lastElementChild;
}

function pretty(o) {
  try { return JSON.stringify(o, null, 2); } catch (e) { return String(o); }
}

// ---------------- SSE 事件绑定 ----------------
function openStream(url) {
  sendBtn.disabled = true;
  aiBubble = null;
  if (es) { es.close(); }
  es = new EventSource(url);

  es.addEventListener('session_start', e => {
    const d = JSON.parse(e.data);
    sessionId = d.sessionId;
    localStorage.setItem('sim_agent_sid', sessionId);
    sidLabel.textContent = 'session: ' + sessionId;
  });

  es.addEventListener('thinking', e => {
    const d = JSON.parse(e.data);
    aiBubble = null;   // 新一轮思考 => 下一个 delta 重开气泡
    addBlock(`<div class="think">⚙️ 第 ${d.step} 步：${d.text || '正在思考……'}</div>`);
  });

  es.addEventListener('assistant_delta', e => {
    const d = JSON.parse(e.data);
    ensureAiBubble().textContent += (d.delta || '');
    scroll();
  });

  es.addEventListener('tool_call', e => {
    const d = JSON.parse(e.data);
    aiBubble = null;
    addBlock(`<div class="tool">🔧 调用工具 <b>${d.toolName}</b>
      <pre>${pretty(d.arguments)}</pre></div>`);
  });

  es.addEventListener('tool_result', e => {
    const d = JSON.parse(e.data);
    addBlock(`<div class="tool ${d.success ? 'ok' : 'err'}">
      ${d.success ? '✅' : '❌'} <b>${d.toolName}</b> — ${d.summary || ''}
      <pre>${pretty(d.data)}</pre></div>`);
  });

  es.addEventListener('node_status', e => {
    const d = JSON.parse(e.data);
    renderNode(d);
  });

  es.addEventListener('confirm_required', e => {
    const d = JSON.parse(e.data);
    aiBubble = null;
    const box = addBlock(`<div class="confirm">⚠️ <b>需要你确认</b>
      <div style="margin-top:6px;white-space:pre-wrap">${d.prompt}</div>
      <button class="btn-ok">确认执行</button>
      <button class="btn-no">取消</button></div>`);
    box.querySelector('.btn-ok').onclick = () => decide(box, true);
    box.querySelector('.btn-no').onclick = () => decide(box, false);
  });

  es.addEventListener('final_answer', e => {
    const d = JSON.parse(e.data);
    aiBubble = null;
    if (d.content) {
      chat.insertAdjacentHTML('beforeend', `<div class="msg ai"><div class="bubble"></div></div>`);
      chat.lastElementChild.querySelector('.bubble').textContent = d.content;
    }
    scroll();
  });

  es.addEventListener('error_event', e => handleErr(e));
  es.addEventListener('error', e => {
    // 注意：EventSource 自带的 onerror 也叫 'error'，这里区分一下
    if (e.data) { handleErr(e); }
  });

  es.addEventListener('done', () => {
    es.close();
    sendBtn.disabled = false;
    input.focus();
  });

  es.onerror = () => {
    // 服务端主动 complete() 也会触发，避免 EventSource 自动重连风暴
    if (es && es.readyState === EventSource.CLOSED) { sendBtn.disabled = false; return; }
    es.close();
    sendBtn.disabled = false;
  };
}

function handleErr(e) {
  try {
    const d = JSON.parse(e.data);
    addBlock(`<div class="tool err">❌ ${d.message || d.error || '发生错误'}</div>`);
  } catch (_) { /* ignore */ }
}

function renderNode(d) {
  if (timeline.querySelector('.hint')) { timeline.innerHTML = ''; }
  let el = nodeEls[d.nodeKey];
  if (!el) {
    timeline.insertAdjacentHTML('beforeend', `<div class="node" data-k="${d.nodeKey}"></div>`);
    el = timeline.lastElementChild;
    nodeEls[d.nodeKey] = el;
  }
  const extra = d.data || {};
  el.innerHTML = `<div><span class="k">${d.nodeKey}</span>
      <span class="s ${d.status}">${d.status}</span></div>
    <div class="hint" style="margin-top:4px">${d.simType || ''}
      ${extra.mcpServer ? ' · ' + extra.mcpServer + '/' + extra.mcpTool : ''}
      ${extra.costMs ? ' · ' + extra.costMs + 'ms' : ''}</div>
    ${extra.error ? `<div class="hint" style="color:#ff8b8b">${extra.error}</div>` : ''}`;
}

function decide(box, approved) {
  box.querySelectorAll('button').forEach(b => b.disabled = true);
  box.insertAdjacentHTML('beforeend',
    `<div class="hint" style="margin-top:8px">${approved ? '已确认，继续执行……' : '已取消'}</div>`);
  openStream(`/api/agent/confirm/stream?sessionId=${encodeURIComponent(sessionId)}`
    + `&approved=${approved}`);
}

function send() {
  const text = input.value.trim();
  if (!text) { return; }
  addUser(text);
  input.value = '';
  Object.keys(nodeEls).forEach(k => delete nodeEls[k]);
  timeline.innerHTML = '<div class="hint">等待节点执行……</div>';
  let url = `/api/agent/chat/stream?message=${encodeURIComponent(text)}`;
  if (sessionId) { url += `&sessionId=${encodeURIComponent(sessionId)}`; }
  openStream(url);
}

sendBtn.onclick = send;
input.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
</script>
</body>
</html>
```

<aside>
🐛

前端两个真实踩过的坑：
① **事件名不能叫 `error`**：`EventSource` 把 `error` 作为内置连接错误事件，自定义 `error` 事件会和它混淆。上面用 `e.data` 存在与否区分，更干净的做法是服务端把事件名改成 `agent_error`。
② **`EventSource` 会自动重连**：服务端 `complete()` 后它会认为“掉线”并重抨一次，导致 Agent 重跑。所以收到 `done` 必须主动 `es.close()`。

</aside>

---

## 9. 启动与验证

```bash
# 1) 启动（默认 mock LLM + mock 仿真，无需任何外部依赖）
mvn -q clean spring-boot:run

# 2) 插件健康检查
curl -s localhost:8080/api/mcp/servers | jq

# 3) 命令行直接看 SSE 流（-N 关闭缓冲，必加）
curl -N "localhost:8080/api/agent/chat/stream?message=%E5%B8%AE%E6%88%91%E8%B7%91%E4%B8%80%E4%B8%AAcoventor%E4%BB%BF%E7%9C%9F"

# 4) 看到 confirm_required 后，另开一个窗口确认
curl -N "localhost:8080/api/agent/confirm/stream?sessionId=s_xxx&approved=true"

# 5) 查实例详情
curl -s localhost:8080/api/workflow/instances/1 | jq

# 6) 浏览器打开 http://localhost:8080/
```

### 接入真实大模型

```bash
export LLM_MOCK=false
export LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
export LLM_API_KEY=sk-xxxxxx
export LLM_MODEL=qwen-plus
mvn spring-boot:run
```

---

## 10. Web 层企业级注意事项

| 项 | 为什么重要 | 本方案做法 |
| --- | --- | --- |
| 不占 Tomcat 线程 | Agent 循环可能跑几分钟 | `agentExecutor` 异步跑，controller 立即返回 emitter |
| 异步超时 | 默认 30s 会把长仿真截断 | `spring.mvc.async.request-timeout: 1800000`  • `SseEmitter(timeout)` |
| 连接注销 | 不注销则 registry 内存泄漏 | `onCompletion/onTimeout/onError` 三个回调全部 unregister |
| 反向代理 | Nginx 默认缓冲会让 SSE “卡住” | Nginx 加 `proxy_buffering off; proxy_read_timeout 1800s;` |
| 鉴权 | 当前 `userId` 是 query 参数 | 接入时改为从 SecurityContext / JWT 取，并校验 session 归属 |
| 频率限制 | 仿真很贵，防止误触发 | 按 userId 限流（如每分钟 3 次 `run_workflow_instance`） |
| 断线重连 | 刷新页面不应丢上下文 | `sessionId` 存 localStorage + `/sessions/{id}/messages` 回放 |

<aside>
💡

**为什么确认后要重新开一条 SSE，而不是复用原连接？** 因为确认可能过很久（人去开会了），长时间挂着的 HTTP 连接会被网关/代理切掉。“**确认前关流、确认后重开**”是更健壮的做法，也天然支持刷新页面后再来确认（待确认动作存在 `agent_session.pending_action` 里，不在内存）。

</aside>