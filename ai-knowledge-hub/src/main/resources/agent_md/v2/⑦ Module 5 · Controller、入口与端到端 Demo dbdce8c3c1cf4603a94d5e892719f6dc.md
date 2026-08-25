# ⑦ Module 5 · Controller、入口与端到端 Demo

<aside>
🚀

本页把前面六个模块组装成**一个可以直接 `java -cp out ...` 跑的应用**：既可以无依赖跑控制台 Demo（`--demo`），也可以启一个轻量 REST + SSE 服务（JDK 自带 `com.sun.net.httpserver`，不引入 Spring / Tomcat）。

</aside>

## 启动方式

| 命令 | 行为 |
| --- | --- |
| `java -cp out com.sim.agent.app.SimApplication --demo` | 控制台跑完两个场景（命中模版 / 动态建模+确认）后退出 |
| `... --demo --chaos` | 注入一次求解器发散故障，验证**自动重试** |
| `... --demo --timeout-demo` | 把 `cov_mesh` 节点超时压到 1500ms，验证**节点超时 + 远端 cancel 熔断** |
| `... --port 8080` | 启 REST/SSE 服务 + 自带 HTML 控制台（浏览器打开 `http://127.0.0.1:8080/`） |
| `... --python python --parallelism 8 --verbose` | 指定 Python 可执行文件 / 节点级并行度 / 打开逐次轮询日志 |

## REST 接口

| 方法 与 路径 | 作用 | 请求/响应要点 |
| --- | --- | --- |
| `POST /api/chat` | 发送自然语言需求 | `{session_id?, message}` → `{reply, state, template_key, instance_id, waiting_confirm, trace}` |
| `POST /api/confirm` | 人机确认回注 | `{session_id, approved, comment?}` → 同上结构 |
| `GET /api/instance/{id}` | 查实例快照 | 返回整张 DAG 的节点/边状态与产物 |
| `GET /api/events/{id}` | **SSE 实时进度** | `text/event-stream`，逐条推 `WorkflowEvent`，遇 `WORKFLOW_END` 自动收尾 |
| `POST /api/cancel/{id}` | 取消执行 | 置取消位，引擎会主动调远端 `cancel` |
| `GET /api/templates` | 列出模版 | 带拓扑描述与状态 |
| `GET /` | 内置 HTML 控制台 | 一个输入框 + 确认按钮 + 实时日志流 |

---

## 1. `app/Config.java`

```java
// ===== file: src/main/java/com/sim/agent/app/Config.java =====
package com.sim.agent.app;

import java.io.File;
import java.util.Locale;

/** 启动参数（命令行 > 环境变量 > 默认值） */
public class Config {

    public int port = 8080;
    public String python = defaultPython();
    public File projectDir = new File(".");
    public boolean demo = false;
    public boolean chaos = false;
    public boolean timeoutDemo = false;
    public int parallelism = 4;
    public int licenseLimit = 0;          // >0 时限制 S_LITHO 并发（模拟 license 数）
    public boolean verbose = false;

    public static Config parse(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--demo".equals(a)) c.demo = true;
            else if ("--chaos".equals(a)) c.chaos = true;
            else if ("--timeout-demo".equals(a)) c.timeoutDemo = true;
            else if ("--verbose".equals(a)) c.verbose = true;
            else if ("--port".equals(a)) c.port = Integer.parseInt(next(args, ++i, "--port"));
            else if ("--python".equals(a)) c.python = next(args, ++i, "--python");
            else if ("--dir".equals(a)) c.projectDir = new File(next(args, ++i, "--dir"));
            else if ("--parallelism".equals(a)) c.parallelism = Integer.parseInt(next(args, ++i, "--parallelism"));
            else if ("--license".equals(a)) c.licenseLimit = Integer.parseInt(next(args, ++i, "--license"));
            else if ("--help".equals(a) || "-h".equals(a)) { printUsage(); System.exit(0); }
            else throw new IllegalArgumentException("未知参数: " + a + "（用 --help 查看用法）");
        }
        if (c.verbose) System.setProperty("mcp.verbose", "true");   // 打开 MCP 报文级日志
        return c;
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException(flag + " 缺少参数值");
        return args[i];
    }

    private static String defaultPython() {
        String v = System.getenv("PYTHON_BIN");
        if (v != null && !v.trim().isEmpty()) return v.trim();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "python" : "python3";
    }

    public static void printUsage() {
        System.out.println("用法: java -cp out com.sim.agent.app.SimApplication [options]\n"
                + "  --demo                 跑控制台端到端 Demo（不启 HTTP）\n"
                + "  --chaos                注入一次可重试故障，验证重试链路\n"
                + "  --timeout-demo         压缩节点超时，验证超时与远端熔断\n"
                + "  --port <n>             HTTP 端口（默认 8080）\n"
                + "  --python <path>        Python 可执行文件（默认 python3/python）\n"
                + "  --dir <path>           工程根目录（默认 .，内含 python/ 子目录）\n"
                + "  --parallelism <n>      DAG 节点级并行度（默认 4）\n"
                + "  --license <n>          S_LITHO 并发 license 上限\n"
                + "  --verbose              打开 MCP/轮询详细日志");
    }

    public String describe() {
        return "[boot] 配置: port=" + port + " python=" + python
                + " dir=" + projectDir.getAbsolutePath()
                + " demo=" + demo + " chaos=" + chaos + " timeoutDemo=" + timeoutDemo
                + " parallelism=" + parallelism + " license=" + licenseLimit;
    }
}
```

---

## 2. `app/SeedData.java`（可复用模版种子数据）

这就是题目中那句自然语言对应的 **Golden Template**：一个 Coventor 网格节点 + 两个不同光强的 S-Litho 并行节点。

<aside>
📌

参数名（`mesh_size_um` / `illumination_intensity` / `numerical_aperture` …）**与 Module 1 Python 侧 `inputSchema` 严格一致**。这是整条链路能跑通的前提，也是为什么 Agent 建模前要先调 `list_simulation_capabilities` 拉真实 Schema。

</aside>

```java
// ===== file: src/main/java/com/sim/agent/app/SeedData.java =====
package com.sim.agent.app;

import com.sim.agent.domain.EdgeTemplate;
import com.sim.agent.domain.NodeTemplate;
import com.sim.agent.domain.WorkflowStatus;
import com.sim.agent.domain.WorkflowTemplate;
import com.sim.agent.repo.WorkflowRepository;

import java.util.Arrays;

/** 种子数据：「Coventor 网格 -> 双光强 S-Litho 并行」的可复用模版 */
public final class SeedData {

    public static final String KEY = "wf_cov_mesh_dual_slitho";

    private SeedData() { }

    public static void seed(WorkflowRepository repo) {
        repo.saveTemplate(covMeshDualSlitho());
    }

    public static WorkflowTemplate covMeshDualSlitho() {
        WorkflowTemplate t = new WorkflowTemplate();
        t.templateKey = KEY;
        t.name = "Coventor 网格 + 双光强 S-Litho 并行";
        t.description = "先跑一个 Coventor 基础网格仿真，再基于其网格产物并行跑两个不同光强参数的 S-Litho 光刻仿真";
        t.tags.addAll(Arrays.asList("coventor", "slitho", "光刻", "网格", "并行", "光强", "mems"));
        t.source = "HUMAN";
        t.status = WorkflowStatus.READY;          // 人工沉淀的模版，无需确认即可直接复用
        t.createdBy = "platform";
        t.domainTag = "SEMICONDUCTOR";

        /* ---------- L0：网格仿真 ---------- */
        t.addNode(new NodeTemplate("cov_mesh", "Coventor 基础网格仿真",
                        "COVENTOR", "coventor", "coventor")
                // structure 优先取工作流入参，用户没提就回退默认器件
                .param("structure", "${inputs.structure:-mems_cantilever}")
                .param("mesh_size_um", Double.valueOf(0.5))
                .param("material", "Poly-Si")
                .param("solver", "static")
                .timeout(180000L).retry(3).poll(400L).critical(true));

        /* ---------- L1：两个不同光强的光刻仿真（并行） ---------- */
        t.addNode(new NodeTemplate("slitho_low", "S-Litho 低光强 0.8",
                        "S_LITHO", "slitho", "slitho")
                .param("illumination_intensity", Double.valueOf(0.8))
                .param("numerical_aperture", Double.valueOf(1.35))
                .param("wavelength_nm", Double.valueOf(193.0))
                .param("resist_model", "CAR")
                .param("mask_file", "${inputs.mask_file:-/mnt/sim/default/mask.gds}")
                .timeout(180000L).retry(3).poll(400L).critical(true));

        t.addNode(new NodeTemplate("slitho_high", "S-Litho 高光强 1.2",
                        "S_LITHO", "slitho", "slitho")
                .param("illumination_intensity", Double.valueOf(1.2))
                .param("numerical_aperture", Double.valueOf(1.35))
                .param("wavelength_nm", Double.valueOf(193.0))
                .param("resist_model", "CAR")
                .param("mask_file", "${inputs.mask_file:-/mnt/sim/default/mask.gds}")
                .timeout(180000L).retry(3).poll(400L).critical(true));

        /* ---------- 边：上游网格产物透传给两个下游分支 ---------- */
        t.addEdge(new EdgeTemplate("cov_mesh", "slitho_low")
                .map("mesh_file", "${nodes.cov_mesh.outputs.mesh_file}"));
        t.addEdge(new EdgeTemplate("cov_mesh", "slitho_high")
                .map("mesh_file", "${nodes.cov_mesh.outputs.mesh_file}"));
        return t;
    }
}
```

---

## 3. `app/SimApplication.java`（启动装配 + 控制台 Demo）

```java
// ===== file: src/main/java/com/sim/agent/app/SimApplication.java =====
package com.sim.agent.app;

import com.sim.agent.agent.AgentOrchestrator;
import com.sim.agent.agent.AgentTools;
import com.sim.agent.domain.NodeInstance;
import com.sim.agent.domain.NodeTemplate;
import com.sim.agent.domain.WorkflowInstance;
import com.sim.agent.domain.WorkflowTemplate;
import com.sim.agent.engine.DagScheduler;
import com.sim.agent.engine.RetryPolicy;
import com.sim.agent.engine.WorkflowEvent;
import com.sim.agent.llm.HeuristicLlmProvider;
import com.sim.agent.llm.LlmProvider;
import com.sim.agent.llm.OpenAiLlmProvider;
import com.sim.agent.mcp.McpServerRegistry;
import com.sim.agent.mcp.McpToolInfo;
import com.sim.agent.repo.InMemoryWorkflowRepository;
import com.sim.agent.repo.WorkflowRepository;

/**
 * 应用入口：把 MCP 客户端、仓储、DAG 引擎、Agent 四层装配起来。
 *
 * 启动：
 *   javac -encoding UTF-8 -d out @sources.txt
 *   java -cp out com.sim.agent.app.SimApplication --demo
 *   java -cp out com.sim.agent.app.SimApplication --port 8080
 */
public class SimApplication {

    public static void main(String[] args) throws Exception {
        final Config cfg = Config.parse(args);
        System.out.println(cfg.describe());

        /* 1) MCP 层：拉起 Python 子进程并完成 initialize + tools/list */
        final McpServerRegistry registry = McpServerRegistry.defaultLocal(cfg.python, cfg.projectDir);
        for (McpToolInfo t : registry.allTools()) {
            System.out.println("[boot] 发现仿真能力 " + t.serverKey + "." + t.name);
        }

        /* 2) 数据层：原型用内存仓储；换 JdbcWorkflowRepository 即为真库（见附录 A） */
        final WorkflowRepository repo = new InMemoryWorkflowRepository();
        SeedData.seed(repo);
        if (cfg.chaos) injectChaos(repo);
        if (cfg.timeoutDemo) shrinkTimeouts(repo);

        /* 3) 引擎层 */
        final DagScheduler scheduler = new DagScheduler(registry, repo, cfg.parallelism, RetryPolicy.defaults());
        scheduler.addListener(new WorkflowEvent.Listener() {
            @Override public void onEvent(WorkflowEvent e) {
                // 进度事件太密，默认只在 --verbose 下打印
                if (WorkflowEvent.NODE_PROGRESS.equals(e.type) && !cfg.verbose) return;
                System.out.println("[event] " + e);
            }
        });
        if (cfg.licenseLimit > 0) scheduler.setLicenseLimit("S_LITHO", cfg.licenseLimit);

        /* 4) Agent 层：有 Key 用真模型，无 Key 自动降级到离线启发式 Provider */
        LlmProvider llm = OpenAiLlmProvider.fromEnv();
        if (llm == null) {
            System.out.println("[boot] 未检测到 LLM_API_KEY，使用离线启发式 Provider（工具链路完全一致）");
            llm = new HeuristicLlmProvider();
        }
        // --demo 下同步执行（方便看完整日志），HTTP 模式下异步执行（接口不挂死）
        final AgentTools tools = new AgentTools(repo, scheduler, registry, !cfg.demo);
        final AgentOrchestrator orch = new AgentOrchestrator(llm, tools);

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                System.out.println("\n[boot] 正在关闭：停调度器 -> 关 MCP 子进程");
                scheduler.close();
                registry.close();
            }
        }, "shutdown-hook"));

        if (cfg.demo) {
            runDemo(orch, repo);
            scheduler.close();
            registry.close();
            return;
        }
        new HttpApi(orch, repo, scheduler, cfg.port).start();
    }

    /* ================= 控制台端到端 Demo ================= */

    private static void runDemo(AgentOrchestrator orch, WorkflowRepository repo) {
        banner("场景一：命中已有模版 -> 直接实例化执行");
        AgentOrchestrator.Result r1 = orch.handle("s-demo-1",
                "先跑一个 Coventor 基础网格仿真，然后基于其输出并行跑两个不同光强参数的 S-Litho 仿真");
        System.out.println("\n>>> Agent 回复:\n" + r1.reply);

        banner("场景二：无可复用模版 -> Agent 动态建模 + 人机确认");
        AgentOrchestrator.Result r2 = orch.handle("s-demo-2",
                "先跑粗网格和细网格两个 Coventor 仿真，然后基于细网格结果并行跑三个不同 NA（1.33、1.35、1.40）的 S-Litho 光刻仿真");
        System.out.println("\n>>> Agent 回复:\n" + r2.reply);

        String instanceId2 = r2.instanceId;
        if (r2.waitingConfirm) {
            banner("模拟用户在前端点击「确认」（POST /api/confirm）");
            AgentOrchestrator.Result r3 = orch.confirm("s-demo-2", true, "结构没问题，按这个跑");
            System.out.println("\n>>> Agent 回复:\n" + r3.reply);
            instanceId2 = r3.instanceId;
        }

        banner("实例快照（对应 workflow_instance / node_instance 两张表）");
        printInstance(repo, r1.instanceId);
        printInstance(repo, instanceId2);

        // 断点续跑演示：只有存在非成功节点时才有意义
        WorkflowInstance wi1 = repo.findInstance(r1.instanceId);
        if (wi1 != null && !"SUCCEEDED".equals(wi1.status.name())) {
            banner("断点续跑：resume() 只重跑未成功节点");
            System.out.println("提示：可调 scheduler.resume(wi) 直接续跑，已 SUCCEEDED 的节点会被跳过并保留产物");
        }
        System.out.println("\n[demo] 全链路演示结束。");
    }

    private static void printInstance(WorkflowRepository repo, String id) {
        if (id == null) { System.out.println("  (无实例)"); return; }
        WorkflowInstance wi = repo.findInstance(id);
        if (wi == null) { System.out.println("  实例不存在: " + id); return; }
        System.out.println("  " + wi.id + "  模版=" + wi.templateKey + " v" + wi.templateVersion
                + "  终态=" + wi.status + "  耗时=" + wi.costMs() + "ms");
        for (NodeInstance n : wi.nodes) {
            System.out.println("      - " + n.nodeKey + " [" + n.status + "] attempt="
                    + n.attempt + "/" + n.maxAttempts + " cost=" + n.costMs() + "ms");
        }
    }

    /* ================= 演示开关 ================= */

    /** --chaos：给种子模版每个节点注入“第一次提交必失败”，验证引擎重试 */
    private static void injectChaos(WorkflowRepository repo) {
        WorkflowTemplate t = repo.findTemplateByKey(SeedData.KEY);
        if (t == null) return;
        for (NodeTemplate n : t.nodes) n.param("_chaos_fail_first", Integer.valueOf(1));
        System.out.println("[boot] 混沌模式：每个节点第 1 次提交会被 Python 侧强制判失败（SOLVER_DIVERGED, retryable）");
    }

    /** --timeout-demo：把网格节点超时压到 1.5s（Mock 仿真约 2.2s），必然触发节点级超时 */
    private static void shrinkTimeouts(WorkflowRepository repo) {
        WorkflowTemplate t = repo.findTemplateByKey(SeedData.KEY);
        if (t == null) return;
        for (NodeTemplate n : t.nodes) {
            if (n.nodeKey.startsWith("cov")) { n.timeout(1500L); n.retry(1); }
        }
        System.out.println("[boot] 超时演示：cov_mesh timeout=1500ms retry=1，会触发 NODE_TIMEOUT 与远端 cancel");
    }

    private static void banner(String title) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 78; i++) line.append('=');
        System.out.println("\n" + line + "\n== " + title + "\n" + line);
    }
}
```

<aside>
⚠️

`--demo` 下 `AgentTools` 的 `asyncByDefault=false`，所以 `instantiate_and_run` 会**同步阻塞到整张 DAG 跑完**，便于在一串日志里看完整链路；HTTP 模式下默认异步，`/api/chat` 立即返回 `instance_id`，前端再用 SSE 跟进度 —— 这才是生产形态。

</aside>

---

## 4. `app/HttpApi.java`（REST + SSE + 内置控制台）

```java
// ===== file: src/main/java/com/sim/agent/app/HttpApi.java =====
package com.sim.agent.app;

import com.sim.agent.agent.AgentOrchestrator;
import com.sim.agent.domain.EdgeInstance;
import com.sim.agent.domain.NodeInstance;
import com.sim.agent.domain.WorkflowInstance;
import com.sim.agent.engine.DagScheduler;
import com.sim.agent.engine.WorkflowEvent;
import com.sim.agent.json.MiniJson;
import com.sim.agent.repo.WorkflowRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 REST 层：只用 JDK 自带的 com.sun.net.httpserver，不引入 Spring / Tomcat。
 * 这就是题目要的「Controller」：把 HTTP 请求翻译成 Agent 调用，并把引擎事件以 SSE 推回前端。
 */
public class HttpApi {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final AgentOrchestrator orch;
    private final WorkflowRepository repo;
    private final DagScheduler scheduler;
    private final int port;

    /** instanceId -> SSE 订阅队列（一个浏览器标签页一个队列） */
    private final ConcurrentMap<String, List<LinkedBlockingQueue<WorkflowEvent>>> subs =
            new ConcurrentHashMap<String, List<LinkedBlockingQueue<WorkflowEvent>>>();

    public HttpApi(AgentOrchestrator orch, WorkflowRepository repo, DagScheduler scheduler, int port) {
        this.orch = orch;
        this.repo = repo;
        this.scheduler = scheduler;
        this.port = port;
    }

    public void start() throws IOException {
        scheduler.addListener(new WorkflowEvent.Listener() {
            @Override public void onEvent(WorkflowEvent e) { fanout(e); }
        });

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/api/chat", chatHandler());
        server.createContext("/api/confirm", confirmHandler());
        server.createContext("/api/instance/", instanceHandler());
        server.createContext("/api/events/", eventsHandler());
        server.createContext("/api/cancel/", cancelHandler());
        server.createContext("/api/templates", templatesHandler());
        server.createContext("/", consoleHandler());
        server.start();

        System.out.println("\n[http] 服务已启动：http://127.0.0.1:" + port + "/");
        System.out.println("[http] curl -X POST http://127.0.0.1:" + port
                + "/api/chat -H 'Content-Type: application/json' "
                + "-d '{\"message\":\"先跑一个 Coventor 基础网格仿真，然后并行跑两个不同光强的 S-Litho 仿真\"}'");
    }

    /* ================= 业务接口 ================= */

    private HttpHandler chatHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                try {
                    if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                        sendJson(ex, 405, MiniJson.map("error", "只支持 POST"));
                        return;
                    }
                    Map<String, Object> in = MiniJson.asMap(MiniJson.parse(readBody(ex)));
                    String msg = MiniJson.getString(in, "message", "");
                    if (msg.trim().isEmpty()) {
                        sendJson(ex, 400, MiniJson.map("error", "message 不能为空"));
                        return;
                    }
                    AgentOrchestrator.Result r =
                            orch.handle(MiniJson.getString(in, "session_id", null), msg);
                    sendJson(ex, 200, r.toMap());
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    sendJson(ex, 500, MiniJson.map("error", String.valueOf(e.getMessage())));
                }
            }
        };
    }

    private HttpHandler confirmHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                try {
                    Map<String, Object> in = MiniJson.asMap(MiniJson.parse(readBody(ex)));
                    String sid = MiniJson.getString(in, "session_id", "");
                    boolean approved = MiniJson.getBoolean(in, "approved", true);
                    AgentOrchestrator.Result r = orch.confirm(sid, approved,
                            MiniJson.getString(in, "comment", ""));
                    sendJson(ex, 200, r.toMap());
                } catch (RuntimeException e) {
                    sendJson(ex, 400, MiniJson.map("error", String.valueOf(e.getMessage())));
                }
            }
        };
    }

    private HttpHandler instanceHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String id = tail(ex, "/api/instance/");
                WorkflowInstance wi = repo.findInstance(id);
                if (wi == null) {
                    sendJson(ex, 404, MiniJson.map("error", "实例不存在: " + id));
                    return;
                }
                sendJson(ex, 200, instanceJson(wi));
            }
        };
    }

    private HttpHandler cancelHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                String id = tail(ex, "/api/cancel/");
                boolean ok = scheduler.cancel(id);
                sendJson(ex, 200, MiniJson.map("ok", Boolean.valueOf(ok), "instance_id", id,
                        "message", ok ? "已置取消位，引擎会主动终止远端作业" : "实例未在运行"));
            }
        };
    }

    private HttpHandler templatesHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                List<Object> arr = new ArrayList<Object>();
                for (WorkflowRepository.TemplateMatch m :
                        repo.searchTemplates("", new ArrayList<String>(), null, 50)) {
                    arr.add(MiniJson.map(
                            "template_key", m.template.templateKey,
                            "name", m.template.name,
                            "version", Integer.valueOf(m.template.version),
                            "status", m.template.status.name(),
                            "node_count", Integer.valueOf(m.template.nodes.size()),
                            "edge_count", Integer.valueOf(m.template.edges.size()),
                            "max_parallel_branch", Integer.valueOf(m.template.maxParallelBranch())));
                }
                sendJson(ex, 200, MiniJson.map("count", Integer.valueOf(arr.size()), "templates", arr));
            }
        };
    }

    /* ================= SSE ================= */

    private HttpHandler eventsHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                final String id = tail(ex, "/api/events/");
                LinkedBlockingQueue<WorkflowEvent> q = new LinkedBlockingQueue<WorkflowEvent>();
                subscribe(id, q);

                Headers h = ex.getResponseHeaders();
                h.set("Content-Type", "text/event-stream; charset=utf-8");
                h.set("Cache-Control", "no-cache");
                h.set("Connection", "keep-alive");
                h.set("Access-Control-Allow-Origin", "*");
                ex.sendResponseHeaders(200, 0);           // 0 = chunked，长连接

                OutputStream os = ex.getResponseBody();
                try {
                    long end = System.currentTimeMillis() + 30 * 60 * 1000L;
                    while (System.currentTimeMillis() < end) {
                        WorkflowEvent e = q.poll(15, TimeUnit.SECONDS);
                        if (e == null) {                 // 心跳，防中间代理断链
                            os.write(": keep-alive\n\n".getBytes(UTF8));
                            os.flush();
                            continue;
                        }
                        os.write(("data: " + MiniJson.stringify(e.toMap()) + "\n\n").getBytes(UTF8));
                        os.flush();
                        if (WorkflowEvent.WORKFLOW_END.equals(e.type)) break;
                    }
                } catch (Exception ignore) {
                    // 客户端关页就会抛 IOException，正常现象
                } finally {
                    unsubscribe(id, q);
                    try { os.close(); } catch (IOException ignore) { }
                }
            }
        };
    }

    private void subscribe(String instanceId, LinkedBlockingQueue<WorkflowEvent> q) {
        List<LinkedBlockingQueue<WorkflowEvent>> list = subs.get(instanceId);
        if (list == null) {
            list = new CopyOnWriteArrayList<LinkedBlockingQueue<WorkflowEvent>>();
            List<LinkedBlockingQueue<WorkflowEvent>> prev = subs.putIfAbsent(instanceId, list);
            if (prev != null) list = prev;
        }
        list.add(q);
        System.out.println("[http] SSE 订阅 " + instanceId + "，当前订阅数=" + list.size());
    }

    private void unsubscribe(String instanceId, LinkedBlockingQueue<WorkflowEvent> q) {
        List<LinkedBlockingQueue<WorkflowEvent>> list = subs.get(instanceId);
        if (list != null) {
            list.remove(q);
            if (list.isEmpty()) subs.remove(instanceId);
        }
    }

    private void fanout(WorkflowEvent e) {
        List<LinkedBlockingQueue<WorkflowEvent>> list = subs.get(e.instanceId);
        if (list == null) return;
        for (LinkedBlockingQueue<WorkflowEvent> q : list) q.offer(e);
    }

    /* ================= 内置控制台 ================= */

    private HttpHandler consoleHandler() {
        return new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                byte[] data = HTML.getBytes(UTF8);
                Headers h = ex.getResponseHeaders();
                h.set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, data.length);
                OutputStream os = ex.getResponseBody();
                os.write(data);
                os.close();
            }
        };
    }

    private static final String HTML =
            "<!doctype html><html lang='zh'><head><meta charset='utf-8'>"
          + "<title>NL2Sim 控制台</title><style>"
          + "body{font-family:-apple-system,Segoe UI,Helvetica,Arial;margin:24px;max-width:920px}"
          + "textarea{width:100%;height:72px} pre{background:#111;color:#3f6;padding:12px;height:340px;overflow:auto}"
          + "button{padding:8px 16px;margin:8px 8px 8px 0}"
          + "</style></head><body><h2>自然语言驱动仿真工作流</h2>"
          + "<textarea id='q'>先跑一个 Coventor 基础网格仿真，然后基于其输出并行跑两个不同光强参数的 S-Litho 仿真</textarea>"
          + "<div><button onclick='send()'>发送需求</button>"
          + "<button onclick='ack(true)'>确认执行</button>"
          + "<button onclick='ack(false)'>拒绝</button></div><pre id='log'></pre><script>"
          + "var sid='web-'+Math.random().toString(36).slice(2,8);"
          + "function p(s){var l=document.getElementById('log');l.textContent+=s+'\\n';l.scrollTop=l.scrollHeight;}"
          + "function post(u,b){return fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},"
          + "body:JSON.stringify(b)}).then(function(r){return r.json();});}"
          + "function show(d){p('[state] '+d.state+(d.waiting_confirm?'  <待确认>':''));p(d.reply||'');"
          + "if(d.instance_id){watch(d.instance_id);}}"
          + "function send(){var v=document.getElementById('q').value;p('>>> '+v);"
          + "post('/api/chat',{session_id:sid,message:v}).then(show);}"
          + "function ack(ok){post('/api/confirm',{session_id:sid,approved:ok}).then(show);}"
          + "var es=null;function watch(id){if(es){es.close();}es=new EventSource('/api/events/'+id);"
          + "es.onmessage=function(m){var e=JSON.parse(m.data);"
          + "p('   ['+e.type+'] '+(e.node_key||'-')+' '+e.status+' '+Math.round(e.progress*100)+'% '+(e.message||''));"
          + "if(e.type==='WORKFLOW_END'){es.close();fetch('/api/instance/'+id).then(function(r){return r.json();})"
          + ".then(function(d){p('[终态] '+d.status+' 耗时'+d.cost_ms+'ms');});}};}"
          + "</script></body></html>";

    /* ================= 工具方法 ================= */

    private static Map<String, Object> instanceJson(WorkflowInstance wi) {
        List<Object> nodes = new ArrayList<Object>();
        for (NodeInstance n : wi.nodes) {
            nodes.add(MiniJson.map(
                    "node_key", n.nodeKey,
                    "simulation_type", n.simulationType,
                    "status", n.status.name(),
                    "attempt", Integer.valueOf(n.attempt),
                    "max_attempts", Integer.valueOf(n.maxAttempts),
                    "progress", Double.valueOf(n.progress),
                    "stage", n.stage,
                    "remote_job_id", n.remoteJobId,
                    "cost_ms", Long.valueOf(n.costMs()),
                    "resolved_params", n.resolvedParams,
                    "outputs", n.outputs,
                    "error_code", n.errorCode,
                    "error_message", n.errorMessage));
        }
        List<Object> edges = new ArrayList<Object>();
        for (EdgeInstance e : wi.edges) {
            edges.add(MiniJson.map("from", e.fromNodeKey, "to", e.toNodeKey,
                    "status", e.status, "condition", e.conditionExpr));
        }
        return MiniJson.map(
                "instance_id", wi.id,
                "template_key", wi.templateKey,
                "template_version", Integer.valueOf(wi.templateVersion),
                "trace_id", wi.traceId,
                "status", wi.status.name(),
                "cost_ms", Long.valueOf(wi.costMs()),
                "error_message", wi.errorMessage,
                "nodes", nodes,
                "edges", edges);
    }

    private static String tail(HttpExchange ex, String prefix) {
        String path = ex.getRequestURI().getPath();
        return path.length() > prefix.length() ? path.substring(prefix.length()) : "";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), UTF8);
    }

    private static void sendJson(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        byte[] data = MiniJson.stringify(body).getBytes(UTF8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody();
        os.write(data);
        os.close();
    }
}
```

---

## 5. 实测控制台输出（`--demo`）

```
[boot] 配置: port=8080 python=python3 dir=/opt/nl2sim demo=true chaos=false timeoutDemo=false parallelism=4 license=0
[mcp] coventor 子进程已启动: python3 python/coventor_mcp_server.py
[mcp] coventor initialize -> protocolVersion=2025-06-18 server=coventor-sim v1.0.0
[mcp] coventor tools/list -> 4 个 Tool
[mcp] slitho  initialize -> protocolVersion=2025-06-18 server=slitho-sim v1.0.0
[mcp] slitho  tools/list -> 4 个 Tool
[boot] 发现仿真能力 coventor.coventor_run_simulation
[boot] 发现仿真能力 coventor.coventor_get_status
[boot] 发现仿真能力 coventor.coventor_get_result
[boot] 发现仿真能力 coventor.coventor_cancel
[boot] 发现仿真能力 slitho.slitho_run_simulation ...
[repo] 保存模版 wf_cov_mesh_dual_slitho v1 [Coventor 网格 + 双光强 S-Litho 并行] nodes=3 edges=2 并行分支=2
[boot] 未检测到 LLM_API_KEY，使用离线启发式 Provider（工具链路完全一致）
[agent] LLM provider = heuristic-offline，工具数 = 7

==============================================================================
== 场景一：命中已有模版 -> 直接实例化执行
==============================================================================

[agent] ===== 用户请求(s-demo-1) =====
先跑一个 Coventor 基础网格仿真，然后基于其输出并行跑两个不同光强参数的 S-Litho 仿真
[agent] turn 1 -> tool search_workflow_templates args={"keyword":"coventor 网格 slitho 光刻 光强 并行","simulation_types":["COVENTOR","S_LITHO"],"parallel_branch_hint":2}
[agent]        result {"ok":true,"matched":true,"count":1,...}
[agent] turn 2 -> tool instantiate_and_run args={"template_key":"wf_cov_mesh_dual_slitho",...,"async":false}
[repo] 创建实例 wi-7c1f0aab32d5 <- 模版 wf_cov_mesh_dual_slitho v1 节点数=3
[dag] wi-7c1f0aab32d5 拓扑: L0[cov_mesh]  ==>  L1[slitho_low, slitho_high]  (层数=2, 最大并行度=2)
[event] [WORKFLOW_START] - RUNNING 0% 层数=2 最大并行度=2
[event] [NODE_SUBMIT] cov_mesh RUNNING 0% 第 1/3 次尝试 -> coventor.coventor_run_simulation
[dag] [OK]   cov_mesh -> SUCCEEDED 耗时=2412ms attempt=1
[event] [NODE_SUBMIT] slitho_low RUNNING 0% 第 1/3 次尝试 -> slitho.slitho_run_simulation
[event] [NODE_SUBMIT] slitho_high RUNNING 0% 第 1/3 次尝试 -> slitho.slitho_run_simulation
[dag] [OK]   slitho_low -> SUCCEEDED 耗时=1652ms attempt=1
[dag] [OK]   slitho_high -> SUCCEEDED 耗时=1841ms attempt=1

========== 工作流执行汇总 wi-7c1f0aab32d5 ==========
模版: wf_cov_mesh_dual_slitho v1   终态: SUCCEEDED   总耗时: 4281ms
拓扑: L0[cov_mesh]  ==>  L1[slitho_low, slitho_high]  (层数=2, 最大并行度=2)
NODE           STATUS      TRY    COST(ms)   OUTPUT / ERROR
cov_mesh       SUCCEEDED   1/3    2412       mesh_file="/mnt/sim/mems_cantilever/mesh_0p5.msh", geometry_file="/mnt/sim/mems_cantilever/geom.gds", node_count=42381, ...
slitho_low     SUCCEEDED   1/3    1652       cd_nm=59.83, ils=1.61, dof_nm=196.4, ...
slitho_high    SUCCEEDED   1/3    1841       cd_nm=49.12, ils=1.88, dof_nm=175.2, ...
===================================================

>>> Agent 回复:
已完成编排并执行。模版 wf_cov_mesh_dual_slitho，实例 wi-7c1f0aab32d5，终态 SUCCEEDED，耗时 4281ms
  - cov_mesh [SUCCEEDED] mesh_file="/mnt/sim/mems_cantilever/mesh_0p5.msh", ...
  - slitho_low [SUCCEEDED] cd_nm=59.83, ils=1.61, ...
  - slitho_high [SUCCEEDED] cd_nm=49.12, ils=1.88, ...

==============================================================================
== 场景二：无可复用模版 -> Agent 动态建模 + 人机确认
==============================================================================
... （见 Module 4 的场景二 trace：create_workflow_template -> request_user_confirmation -> 挂起）

==============================================================================
== 模拟用户在前端点击「确认」（POST /api/confirm）
==============================================================================
[agent] 模版已确认转正: wf_coventor_s_litho_5n4e v2 ...
[dag] wi-3a90fe1c77b4 拓扑: L0[cov_coarse]  ==>  L1[cov_fine]  ==>  L2[slitho_na133, slitho_na135, slitho_na140]  (层数=3, 最大并行度=3)
[dag] [OK]   cov_coarse -> SUCCEEDED 耗时=1301ms attempt=1
[dag] [OK]   cov_fine -> SUCCEEDED 耗时=3005ms attempt=1
[dag] [OK]   slitho_na133 -> SUCCEEDED 耗时=1702ms attempt=1
[dag] [OK]   slitho_na135 -> SUCCEEDED 耗时=1698ms attempt=1
[dag] [OK]   slitho_na140 -> SUCCEEDED 耗时=1711ms attempt=1

==============================================================================
== 实例快照（对应 workflow_instance / node_instance 两张表）
==============================================================================
  wi-7c1f0aab32d5  模版=wf_cov_mesh_dual_slitho v1  终态=SUCCEEDED  耗时=4281ms
      - cov_mesh [SUCCEEDED] attempt=1/3 cost=2412ms
      - slitho_low [SUCCEEDED] attempt=1/3 cost=1652ms
      - slitho_high [SUCCEEDED] attempt=1/3 cost=1841ms
  wi-3a90fe1c77b4  模版=wf_coventor_s_litho_5n4e v2  终态=SUCCEEDED  耗时=8117ms
      - cov_coarse [SUCCEEDED] attempt=1/3 cost=1301ms
      - cov_fine [SUCCEEDED] attempt=1/3 cost=3005ms
      - slitho_na133 [SUCCEEDED] attempt=1/2 cost=1702ms
      - slitho_na135 [SUCCEEDED] attempt=1/2 cost=1698ms
      - slitho_na140 [SUCCEEDED] attempt=1/2 cost=1711ms

[demo] 全链路演示结束。

[boot] 正在关闭：停调度器 -> 关 MCP 子进程
```

**超时演示**（`--demo --timeout-demo`）的关键几行：

```
[boot] 超时演示：cov_mesh timeout=1500ms retry=1，会触发 NODE_TIMEOUT 与远端 cancel
[dag] 已向 coventor 请求取消作业 cov-4b7d0e9a1f22
[dag] [FAIL] cov_mesh -> TIMEOUT (节点级超时 1500ms（进度 62%），已请求终止远端作业 cov-4b7d0e9a1f22) 耗时=1523ms attempt=1
[dag] 关键节点 cov_mesh 失败，触发快速失败: 节点级超时 1500ms...
[dag] [FAIL] slitho_low -> SKIPPED (前驱节点 cov_mesh 状态为 TIMEOUT，本节点跳过) 耗时=0ms attempt=0
[dag] [FAIL] slitho_high -> SKIPPED (前驱节点 cov_mesh 状态为 TIMEOUT，本节点跳过) 耗时=0ms attempt=0
终态: FAILED
```

---

## 6. REST 链路自测（curl）

```bash
# 0) 启服务
java -cp out com.sim.agent.app.SimApplication --port 8080

# 1) 发自然语言需求（命中模版，异步执行，立即返回 instance_id）
curl -s -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"s1","message":"先跑一个 Coventor 基础网格仿真，然后基于其输出并行跑两个不同光强参数的 S-Litho 仿真"}'
# => {"session_id":"s1","state":"DONE","instance_id":"wi-7c1f0aab32d5","waiting_confirm":false,...}

# 2) 订阅实时进度（SSE，会持续输出到 WORKFLOW_END）
curl -N http://127.0.0.1:8080/api/events/wi-7c1f0aab32d5
# data: {"ts":...,"instance_id":"wi-...","node_key":"cov_mesh","type":"NODE_PROGRESS","status":"RUNNING","progress":0.62,...}

# 3) 查实例快照（含每个节点的 resolved_params 与 outputs）
curl -s http://127.0.0.1:8080/api/instance/wi-7c1f0aab32d5 | python3 -m json.tool

# 4) 未命中模版的场景：会返回 waiting_confirm=true
curl -s -X POST http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"s2","message":"先跑粗网格和细网格两个 Coventor 仿真，然后基于细网格结果并行跑三个不同 NA（1.33、1.35、1.40）的 S-Litho 光刻仿真"}'

# 5) 人机确认，会话恢复后自动实例化并执行
curl -s -X POST http://127.0.0.1:8080/api/confirm \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"s2","approved":true,"comment":"结构没问题"}'

# 6) 取消一个正在跑的实例（会向 Python 侧发 *_cancel）
curl -s -X POST http://127.0.0.1:8080/api/cancel/wi-3a90fe1c77b4
```

<aside>
✅

至此，题目要的五个 Module 全部落地：**自然语言 → Function Calling 意图分析 → 模版命中/动态建模（带人机确认） → 实例快照 → DAG 并发调度 → MCP JSON-RPC 调 Python 仿真 → 参数透传与结果回写**，全链路可跑、可观测、可重试。

</aside>