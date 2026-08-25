# ① Module 1 · Python Mock MCP Server（stdio + HTTP 双传输，零依赖）

<aside>
✅

三个文件，**只用 Python 标准库**（不需 `pip install mcp`），直接实现 MCP 的 JSON-RPC 2.0 服务端。选择手写而非官方 SDK 的原因：工业现场常见离网 / 老版 Python 环境，且手写能清楚展示协议细节，方便 Java 侧对齐。页尾附官方 SDK / FastMCP 的**等价写法**，可一键替换。

</aside>

## 0. 仿真 Tool 接口设计（三段式）

真实仿真动辄数十分钟，**绝不能用一个同步 Tool 长挂**（JSON-RPC 请求会被任何网关/超时断掉）。因此每个仿真 Server 提供 4 个 Tool：

| Tool | 语义 | Java 侧调用时机 |
| --- | --- | --- |
| `{prefix}_run_simulation` | 提交作业，**立即**返回 `job_id` | 节点开始执行 |
| `{prefix}_get_status` | 查询状态/进度/阶段 | 轮询循环（默认 400ms） |
| `{prefix}_get_result` | 拉取终态产物 | 状态转 SUCCEEDED 后 |
| `{prefix}_cancel` | 取消作业 | 节点超时熔断 / 工作流取消 |

---

## 1. `python/mcp_common.py`

极简 MCP 服务端框架 + Mock 长作业池，两个仿真 Server 共用。

```python
# -*- coding: utf-8 -*-
"""
极简 MCP(Model Context Protocol) 服务端框架 —— 仅依赖 Python 标准库。

传输：
  1) stdio : 换行分隔的 JSON-RPC 2.0（由 Java ProcessBuilder 拉起，推荐同机部署）
  2) http  : POST /mcp 单端点（Streamable HTTP 简化实现），GET /sse 心跳事件流

已实现的协议方法：
  initialize / notifications/initialized / ping / tools/list / tools/call
  resources/list / prompts/list / shutdown

铁律：stdio 模式下 stdout 只允许输出 JSON-RPC 报文，任何日志必须走 stderr。
"""
import argparse
import json
import os
import sys
import threading
import time
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL_VERSION = "2025-06-18"
SUPPORTED_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")

def log(fmt, *args):
    """所有日志必须写 stderr，否则会污染 stdio 上的 JSON-RPC 流。"""
    try:
        msg = (fmt % args) if args else str(fmt)
    except Exception:
        msg = str(fmt)
    sys.stderr.write("[mcp] %s\n" % msg)
    sys.stderr.flush()

class ToolError(Exception):
    """工具业务异常 -> tools/call 以 result.isError=true 返回（协议层仍是成功响应）"""

    def __init__(self, message, code="TOOL_ERROR", retryable=False):
        Exception.__init__(self, message)
        self.code = code
        self.retryable = retryable

class RpcError(Exception):
    """协议层异常 -> JSON-RPC error 对象"""

    def __init__(self, code, message, data=None):
        Exception.__init__(self, message)
        self.code = code
        self.message = message
        self.data = data

# ============================================================
# Mock 长作业池：提交即返回，状态/进度按墙钟时间推演
# ============================================================
DEFAULT_STAGES = ["queued", "preprocess", "meshing", "solving", "postprocess", "done"]

class JobStore:

    def __init__(self, kind, stages=None):
        self.kind = kind
        self.stages = stages or DEFAULT_STAGES
        self._jobs = {}
        self._chaos_seen = {}
        self._lock = threading.RLock()

    def submit(self, params, duration_s, result_fn, meta=None):
        meta = meta or {}
        job_id = "%s-%s" % (self.kind, uuid.uuid4().hex[:12])
        duration = max(0.2, float(duration_s))

        # 混沌注入：同一个 node_key 的前 N 次提交强制失败，用于验证 Java 侧重试逻辑
        fail_at = None
        chaos_n = int(params.get("_chaos_fail_first")
                      or os.environ.get("SIM_CHAOS_FAIL_FIRST") or 0)
        if chaos_n > 0:
            key = "%s::%s" % (self.kind, meta.get("node_key", "-"))
            with self._lock:
                seen = self._chaos_seen.get(key, 0) + 1
                self._chaos_seen[key] = seen
            if seen <= chaos_n:
                fail_at = 0.6

        with self._lock:
            self._jobs[job_id] = {
                "job_id": job_id,
                "params": dict(params),
                "meta": dict(meta),
                "created_at": time.time(),
                "duration": duration,
                "result_fn": result_fn,
                "fail_at": fail_at,
                "cancelled": False,
                "cached": None,
            }
        log("submit %s duration=%.2fs node=%s chaos_fail=%s",
            job_id, duration, meta.get("node_key"), fail_at is not None)
        return job_id

    def _job(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
        if job is None:
            raise ToolError("job_id 不存在: %s" % job_id, code="JOB_NOT_FOUND")
        return job

    def _phase(self, job):
        elapsed = time.time() - job["created_at"]
        progress = min(1.0, elapsed / job["duration"])
        if job["cancelled"]:
            return "CANCELLED", progress, elapsed
        if job["fail_at"] is not None and progress >= job["fail_at"]:
            return "FAILED", job["fail_at"], elapsed
        if progress >= 1.0:
            return "SUCCEEDED", 1.0, elapsed
        return "RUNNING", progress, elapsed

    def status(self, job_id):
        job = self._job(job_id)
        st, progress, elapsed = self._phase(job)
        idx = min(len(self.stages) - 1, int(progress * (len(self.stages) - 1)))
        out = {
            "ok": st != "FAILED",
            "job_id": job_id,
            "status": st,
            "progress": round(progress, 3),
            "stage": "done" if st == "SUCCEEDED" else self.stages[idx],
            "elapsed_ms": int(elapsed * 1000),
        }
        if st == "FAILED":
            out["error_code"] = "SOLVER_DIVERGED"
            out["error_message"] = "求解器在第 %d 迭代步残差发散（Mock 注入的可重试故障）" % int(progress * 100)
            out["retryable"] = True
        return out

    def result(self, job_id):
        job = self._job(job_id)
        st, progress, _ = self._phase(job)
        if st == "RUNNING":
            raise ToolError("作业尚未结束，当前进度 %.0f%%，请继续轮询 get_status" % (progress * 100),
                            code="JOB_NOT_FINISHED")
        if st == "CANCELLED":
            raise ToolError("作业已被取消: %s" % job_id, code="JOB_CANCELLED")
        if st == "FAILED":
            raise ToolError("作业失败: 求解器发散", code="SOLVER_DIVERGED", retryable=True)
        with self._lock:
            if job["cached"] is None:
                job["cached"] = job["result_fn"](job["params"])
        return {
            "ok": True,
            "job_id": job_id,
            "status": "SUCCEEDED",
            "cost_ms": int(job["duration"] * 1000),
            "outputs": job["cached"],
        }

    def cancel(self, job_id):
        job = self._job(job_id)
        with self._lock:
            job["cancelled"] = True
        log("cancel %s", job_id)
        return {"ok": True, "job_id": job_id, "status": "CANCELLED"}

# ============================================================
# MCP 服务端
# ============================================================
class McpServer:

    def __init__(self, name, version="1.0.0", instructions=""):
        self.name = name
        self.version = version
        self.instructions = instructions
        self._tools = {}
        self._order = []
        self.client_info = None
        self.initialized = False
        self.session_id = uuid.uuid4().hex

    # ---------- 工具注册 ----------
    def tool(self, name, description, input_schema, output_schema=None, title=None):
        def deco(fn):
            self._tools[name] = {
                "name": name,
                "title": title or name,
                "description": description,
                "inputSchema": input_schema,
                "outputSchema": output_schema,
                "handler": fn,
            }
            self._order.append(name)
            return fn
        return deco

    def _tool_meta(self, name):
        t = self._tools[name]
        meta = {
            "name": t["name"],
            "title": t["title"],
            "description": t["description"],
            "inputSchema": t["inputSchema"],
            "annotations": {"readOnlyHint": name.endswith("_get_status") or name.endswith("_get_result"),
                            "openWorldHint": False},
        }
        if t["outputSchema"]:
            meta["outputSchema"] = t["outputSchema"]
        return meta

    # ---------- JSON-RPC 分发 ----------
    def handle(self, msg):
        """返回响应 dict；如果是通知则返回 None"""
        if not isinstance(msg, dict):
            return self._error(None, -32600, "Invalid Request: 报文必须是对象")
        is_notification = "id" not in msg
        mid = msg.get("id")
        method = msg.get("method")
        params = msg.get("params") or {}
        try:
            if not method:
                raise RpcError(-32600, "Invalid Request: 缺少 method")
            if method == "initialize":
                result = self._initialize(params)
            elif method == "notifications/initialized":
                self.initialized = True
                log("client initialized: %s", self.client_info)
                return None
            elif method.startswith("notifications/"):
                return None
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": [self._tool_meta(n) for n in self._order]}
            elif method == "tools/call":
                result = self._call_tool(params)
            elif method == "resources/list":
                result = {"resources": []}
            elif method == "prompts/list":
                result = {"prompts": []}
            elif method == "shutdown":
                result = {}
            else:
                raise RpcError(-32601, "Method not found: %s" % method)
        except RpcError as e:
            return None if is_notification else self._error(mid, e.code, e.message, e.data)
        except Exception as e:
            log("internal error:\n%s", traceback.format_exc())
            return None if is_notification else self._error(mid, -32603, "Internal error: %s" % e)
        return None if is_notification else {"jsonrpc": "2.0", "id": mid, "result": result}

    def _initialize(self, params):
        requested = params.get("protocolVersion") or PROTOCOL_VERSION
        negotiated = requested if requested in SUPPORTED_VERSIONS else PROTOCOL_VERSION
        self.client_info = params.get("clientInfo")
        log("initialize requested=%s negotiated=%s", requested, negotiated)
        return {
            "protocolVersion": negotiated,
            "capabilities": {"tools": {"listChanged": False}, "logging": {}},
            "serverInfo": {"name": self.name, "version": self.version},
            "instructions": self.instructions,
        }

    def _call_tool(self, params):
        name = params.get("name")
        args = params.get("arguments")
        if args is None:
            args = {}
        if name not in self._tools:
            raise RpcError(-32602, "Unknown tool: %s" % name)
        if not isinstance(args, dict):
            raise RpcError(-32602, "arguments 必须是对象")
        args = dict(args)
        meta = args.pop("_meta", None) or {}     # Java 引擎传入的追踪上下文
        try:
            out = self._tools[name]["handler"](args, meta)
        except ToolError as e:
            log("tool %s business error: %s", name, e)
            return {
                "content": [{"type": "text", "text": "TOOL_ERROR[%s] %s" % (e.code, e)}],
                "structuredContent": {"ok": False, "error_code": e.code,
                                      "error_message": str(e), "retryable": bool(e.retryable)},
                "isError": True,
            }
        return {
            "content": [{"type": "text", "text": json.dumps(out, ensure_ascii=False)}],
            "structuredContent": out,
            "isError": False,
        }

    @staticmethod
    def _error(mid, code, message, data=None):
        err = {"code": code, "message": message}
        if data is not None:
            err["data"] = data
        return {"jsonrpc": "2.0", "id": mid, "error": err}

    # ---------- 传输 1：stdio ----------
    def _write_stdout(self, obj):
        # ensure_ascii=True：上线全 ASCII，彻底避开 Windows/转码问题（Java 侧会解 \\uXXXX）
        sys.stdout.write(json.dumps(obj, ensure_ascii=True) + "\n")
        sys.stdout.flush()

    def serve_stdio(self):
        log("[%s v%s] stdio serving, tools=%s", self.name, self.version, self._order)
        for raw in sys.stdin:
            line = raw.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except Exception:
                self._write_stdout(self._error(None, -32700, "Parse error"))
                continue
            if isinstance(msg, list):                      # JSON-RPC 批量
                batch = [r for r in (self.handle(m) for m in msg) if r is not None]
                if batch:
                    self._write_stdout(batch)
            else:
                resp = self.handle(msg)
                if resp is not None:
                    self._write_stdout(resp)
        log("[%s] stdin 已关闭，退出", self.name)

    # ---------- 传输 2：Streamable HTTP ----------
    def serve_http(self, host="127.0.0.1", port=9000):
        mcp = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *a):
                log("http " + (fmt % a))

            def _send(self, code, body, ctype="application/json; charset=utf-8"):
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Mcp-Session-Id", mcp.session_id)
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def do_POST(self):
                if self.path.rstrip("/") not in ("/mcp", ""):
                    self._send(404, b'{"error":"not found"}')
                    return
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n).decode("utf-8") if n else ""
                try:
                    msg = json.loads(raw)
                except Exception:
                    self._send(400, json.dumps(McpServer._error(None, -32700, "Parse error")).encode("utf-8"))
                    return
                if isinstance(msg, list):
                    out = [r for r in (mcp.handle(m) for m in msg) if r is not None]
                    body = json.dumps(out, ensure_ascii=True).encode("utf-8") if out else b""
                else:
                    resp = mcp.handle(msg)
                    body = json.dumps(resp, ensure_ascii=True).encode("utf-8") if resp is not None else b""
                if not body:
                    self._send(202, b"")      # 纯通知：202 无体
                else:
                    self._send(200, body)

            def do_GET(self):
                if self.path.startswith("/sse"):
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    try:
                        for _ in range(3600):
                            self.wfile.write(b": keep-alive\n\n")
                            self.wfile.flush()
                            time.sleep(1)
                    except Exception:
                        pass
                    return
                info = {"server": mcp.name, "version": mcp.version, "tools": mcp._order}
                self._send(200, json.dumps(info, ensure_ascii=True).encode("utf-8"))

        httpd = ThreadingHTTPServer((host, port), Handler)
        log("[%s v%s] HTTP MCP endpoint -> http://%s:%d/mcp", self.name, self.version, host, port)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            httpd.server_close()

# ============================================================
# 公用：作业类 Tool 注册器 + 启动入口
# ============================================================
JOB_ID_SCHEMA = {
    "type": "object",
    "properties": {"job_id": {"type": "string", "description": "提交仿真时返回的作业 ID"}},
    "required": ["job_id"],
}

def register_job_tools(srv, jobs, prefix):
    """为任意仿真 Server 注册标准的 get_status / get_result / cancel 三件套"""

    @srv.tool(prefix + "_get_status",
              "查询仿真作业的实时状态与进度。status 取值：RUNNING / SUCCEEDED / FAILED / CANCELLED。",
              JOB_ID_SCHEMA)
    def _get_status(args, meta):
        return jobs.status(args.get("job_id"))

    @srv.tool(prefix + "_get_result",
              "拉取已完成仿真作业的结果产物。作业未结束时返回 isError=true。",
              JOB_ID_SCHEMA)
    def _get_result(args, meta):
        return jobs.result(args.get("job_id"))

    @srv.tool(prefix + "_cancel",
              "取消一个仿真作业（引擎超时熔断或用户中止时调用）。",
              JOB_ID_SCHEMA)
    def _cancel(args, meta):
        return jobs.cancel(args.get("job_id"))

def run(server):
    p = argparse.ArgumentParser(description="Mock 仿真 MCP Server")
    p.add_argument("--transport", default=os.environ.get("MCP_TRANSPORT", "stdio"),
                   choices=["stdio", "http"])
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9000)
    a = p.parse_args()
    if a.transport == "stdio":
        server.serve_stdio()
    else:
        server.serve_http(a.host, a.port)
```

---

## 2. `python/coventor_mcp_server.py`

```python
# -*- coding: utf-8 -*-
"""Coventor 结构力学 / 网格划分仿真 Mock MCP Server"""
import hashlib

from mcp_common import JobStore, McpServer, ToolError, register_job_tools, run

srv = McpServer(
    name="coventor-sim",
    version="1.0.0",
    instructions=("MEMS 结构力学与网格划分仿真原子能力。标准用法："
                  "coventor_run_simulation 提交 -> coventor_get_status 轮询 -> coventor_get_result 取产物。"),
)
jobs = JobStore("cov", stages=["queued", "geometry", "meshing", "solving", "postprocess", "done"])

RUN_SCHEMA = {
    "type": "object",
    "properties": {
        "structure": {"type": "string",
                      "description": "待仿真的器件/结构名，如 mems_cantilever、rf_switch"},
        "mesh_size_um": {"type": "number", "default": 0.5,
                         "description": "网格尺寸(微米)，越小越精细也越慢，取值 (0, 50]"},
        "material": {"type": "string", "enum": ["Si", "SiO2", "Poly-Si", "Al"], "default": "Si"},
        "solver": {"type": "string", "enum": ["static", "modal", "transient"], "default": "static"},
        "mock_duration_s": {"type": "number", "description": "测试用：强制指定仿真耗时秒数"},
        "_chaos_fail_first": {"type": "integer", "default": 0,
                              "description": "测试用：同一节点前 N 次提交强制失败，用于验证重试"},
    },
    "required": ["structure"],
}

RUN_OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "job_id": {"type": "string"},
        "status": {"type": "string"},
        "eta_ms": {"type": "integer"},
    },
}

def _rand01(*parts):
    """参数指纹 -> [0,1) 伪随机数：保证同参数多次仿真结果一致（可复现）"""
    raw = "|".join(str(p) for p in parts).encode("utf-8")
    return int(hashlib.md5(raw).hexdigest()[:8], 16) / float(0xFFFFFFFF)

@srv.tool("coventor_run_simulation",
          "提交一次 Coventor 结构/网格仿真作业，立即返回 job_id（异步作业，不阻塞）。"
          "产物包含网格文件路径 mesh_file、节点数、最大应力与一阶模态频率。",
          RUN_SCHEMA, output_schema=RUN_OUTPUT_SCHEMA, title="Coventor 结构仿真")
def coventor_run_simulation(args, meta):
    structure = args.get("structure")
    if not structure:
        raise ToolError("structure 为必填参数", code="INVALID_PARAM")
    try:
        mesh = float(args.get("mesh_size_um", 0.5) or 0.5)
    except Exception:
        raise ToolError("mesh_size_um 必须是数字", code="INVALID_PARAM")
    if mesh <= 0 or mesh > 50:
        raise ToolError("mesh_size_um 必须在 (0, 50] 区间，当前=%s" % mesh, code="INVALID_PARAM")

    material = args.get("material") or "Si"
    solver = args.get("solver") or "static"
    duration = float(args.get("mock_duration_s") or min(3.0, 1.0 + 0.6 / mesh))

    def result_fn(p):
        r = _rand01(structure, mesh, material, solver)
        node_count = int(20000 / mesh + r * 5000)
        return {
            "mesh_file": "/mnt/sim/%s/mesh_%s.msh" % (structure, str(mesh).replace(".", "p")),
            "geometry_file": "/mnt/sim/%s/geom.gds" % structure,
            "node_count": node_count,
            "element_count": int(node_count * 5.7),
            "mesh_quality": round(0.80 + r * 0.17, 3),
            "max_stress_mpa": round(120 + r * 80, 2),
            "max_displacement_nm": round(30 + r * 45, 2),
            "first_mode_khz": round(88 + r * 25, 3),
            "material": material,
            "solver": solver,
        }

    job_id = jobs.submit(args, duration, result_fn, meta)
    return {"ok": True, "job_id": job_id, "status": "RUNNING", "eta_ms": int(duration * 1000),
            "accepted_params": {"structure": structure, "mesh_size_um": mesh,
                                "material": material, "solver": solver}}

register_job_tools(srv, jobs, "coventor")

if __name__ == "__main__":
    run(srv)
```

---

## 3. `python/slitho_mcp_server.py`

```python
# -*- coding: utf-8 -*-
"""S-Litho 光刻成像仿真 Mock MCP Server"""
import hashlib

from mcp_common import JobStore, McpServer, ToolError, register_job_tools, run

srv = McpServer(
    name="slitho-sim",
    version="1.0.0",
    instructions=("光刻成像/工艺窗口仿真原子能力。典型上游依赖：Coventor 产出的 mesh_file。"
                  "标准用法：slitho_run_simulation -> slitho_get_status -> slitho_get_result。"),
)
jobs = JobStore("lit", stages=["queued", "mask_load", "source_setup", "imaging", "resist_dev", "done"])

RUN_SCHEMA = {
    "type": "object",
    "properties": {
        "mask_file": {"type": "string", "description": "掩模/版图文件路径"},
        "mesh_file": {"type": "string",
                      "description": "上游 Coventor 产出的网格文件路径（参数透传入口）"},
        "illumination_intensity": {"type": "number", "default": 1.0,
                                   "description": "归一化光强，取值 (0, 3]"},
        "numerical_aperture": {"type": "number", "default": 1.35, "description": "数值孔径 NA"},
        "wavelength_nm": {"type": "number", "default": 193.0},
        "resist_model": {"type": "string", "enum": ["CAR", "MOx", "NTD"], "default": "CAR"},
        "defocus_nm": {"type": "number", "default": 0.0},
        "mock_duration_s": {"type": "number", "description": "测试用：强制指定仿真耗时秒数"},
        "_chaos_fail_first": {"type": "integer", "default": 0,
                              "description": "测试用：同一节点前 N 次提交强制失败"},
    },
    "required": ["illumination_intensity"],
}

def _rand01(*parts):
    raw = "|".join(str(p) for p in parts).encode("utf-8")
    return int(hashlib.md5(raw).hexdigest()[:8], 16) / float(0xFFFFFFFF)

@srv.tool("slitho_run_simulation",
          "提交一次 S-Litho 光刻成像仿真作业，立即返回 job_id。"
          "产物包含关键尺寸 cd_nm、归一化图像对数斜率 ils、焦深 dof_nm 与轮廓文件。",
          RUN_SCHEMA, title="S-Litho 光刻仿真")
def slitho_run_simulation(args, meta):
    try:
        intensity = float(args.get("illumination_intensity", 1.0) or 1.0)
    except Exception:
        raise ToolError("illumination_intensity 必须是数字", code="INVALID_PARAM")
    if intensity <= 0 or intensity > 3:
        raise ToolError("illumination_intensity 必须在 (0, 3] 区间，当前=%s" % intensity,
                        code="INVALID_PARAM")

    na = float(args.get("numerical_aperture", 1.35) or 1.35)
    wl = float(args.get("wavelength_nm", 193.0) or 193.0)
    resist = args.get("resist_model") or "CAR"
    mesh_file = args.get("mesh_file") or ""
    mask_file = args.get("mask_file") or "/mnt/sim/default/mask.gds"
    defocus = float(args.get("defocus_nm", 0.0) or 0.0)
    duration = float(args.get("mock_duration_s") or (1.2 + intensity * 0.5))

    def result_fn(p):
        r = _rand01(mask_file, mesh_file, intensity, na, wl, resist)
        # 简化的物理相关性：光强↑/NA↑ -> CD ↓、ILS ↑、DOF ↓
        k1 = 0.61 * wl / (na * 1000.0)
        cd = 65.0 / (0.75 + intensity * 0.45) * (1.35 / na) + r * 3.0 - abs(defocus) * 0.004
        ils = 1.05 + intensity * 0.65 + (na - 1.35) * 0.8 - abs(defocus) * 0.0009 + r * 0.08
        dof = 220.0 / (0.8 + intensity * 0.4) * (1.35 / na) + r * 12.0
        return {
            "cd_nm": round(cd, 2),
            "ils": round(ils, 3),
            "dof_nm": round(dof, 1),
            "k1_factor": round(k1, 4),
            "exposure_latitude_pct": round(6.0 + intensity * 3.2 + r * 1.5, 2),
            "mee_nm_per_pct": round(1.9 - intensity * 0.35 + r * 0.2, 3),
            "contour_file": "/mnt/sim/litho/contour_i%s_na%s.txt"
                            % (str(intensity).replace(".", "p"), str(na).replace(".", "p")),
            "aerial_image_file": "/mnt/sim/litho/aerial_i%s.png" % str(intensity).replace(".", "p"),
            "upstream_mesh_file": mesh_file,
            "illumination_intensity": intensity,
            "numerical_aperture": na,
            "resist_model": resist,
        }

    job_id = jobs.submit(args, duration, result_fn, meta)
    return {"ok": True, "job_id": job_id, "status": "RUNNING", "eta_ms": int(duration * 1000),
            "accepted_params": {"illumination_intensity": intensity, "numerical_aperture": na,
                                "wavelength_nm": wl, "resist_model": resist,
                                "mesh_file": mesh_file, "mask_file": mask_file}}

register_job_tools(srv, jobs, "slitho")

if __name__ == "__main__":
    run(srv)
```

---

## 4. 自测（不依赖 Java）

### 4.1 stdio 裸协议冒烟

```bash
cd nl2sim
printf '%s\n' \
 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}' \
 '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
 '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"slitho_run_simulation","arguments":{"illumination_intensity":1.2,"mock_duration_s":0.5,"_meta":{"node_key":"smoke"}}}}' \
 | python3 python/slitho_mcp_server.py
```

预期 stdout（四行 JSON，日志全在 stderr）：

```json
{"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-06-18", "capabilities": {"tools": {"listChanged": false}, "logging": {}}, "serverInfo": {"name": "slitho-sim", "version": "1.0.0"}, "instructions": "..."}}
{"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": "slitho_run_simulation", "...": "..."}, {"name": "slitho_get_status"}, {"name": "slitho_get_result"}, {"name": "slitho_cancel"}]}}
{"jsonrpc": "2.0", "id": 3, "result": {"content": [{"type": "text", "text": "{\"ok\": true, \"job_id\": \"lit-9f2c...\", \"status\": \"RUNNING\", \"eta_ms\": 500}"}], "structuredContent": {"ok": true, "job_id": "lit-9f2c...", "status": "RUNNING", "eta_ms": 500}, "isError": false}}
```

### 4.2 HTTP 传输冒烟

```bash
python3 python/coventor_mcp_server.py --transport http --port 9001 &
curl -s -X POST http://127.0.0.1:9001/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' -i | head -20
# 响应头会带 Mcp-Session-Id，Java 侧会自动回带
```

<aside>
⚠️

**三个最容易踩的坑**（1）stdio 模式下在 Python 里写了 `print()` → 污染协议流，Java 侧报解析异常（本实现已在 Java 侧加了 `stdout-noise` 容错过滤）；（2）忘记 `flush()` → Java 阻塞到超时（本实现双保险：`flush()` + `PYTHONUNBUFFERED=1`）；（3）把工具业务失败写成 JSON-RPC `error` → 语义错误。协议级错误用 `error`（如方法不存在），**业务失败必须用 `result.isError=true`**，否则 Agent 无法区分「协议坏了」和「仿真发散了」。

</aside>

---

## 5. 如果想改用官方 SDK / FastMCP

业务逻辑零侵入，Java 侧**无需任何改动**（协议一致）：

```python
# pip install "mcp[cli]"
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("coventor-sim")

@mcp.tool()
def coventor_run_simulation(structure: str, mesh_size_um: float = 0.5,
                           material: str = "Si", solver: str = "static") -> dict:
    """提交一次 Coventor 结构/网格仿真作业，立即返回 job_id。"""
    job_id = jobs.submit({"structure": structure, "mesh_size_um": mesh_size_um}, ...)
    return {"ok": True, "job_id": job_id, "status": "RUNNING"}

if __name__ == "__main__":
    mcp.run()                      # stdio
    # mcp.run(transport="streamable-http", host="0.0.0.0", port=9001)
```

| 对比维度 | 本文手写实现 | 官方 SDK / FastMCP |
| --- | --- | --- |
| 依赖 | 零（纯标准库，适合离网产线机） | 需 `pip install`，Python ≥ 3.10 |
| Schema | 手写 JSON Schema，可完全控制描述词 | 类型注解自动推导 |
| 协议演进 | 需自行跟进 | SDK 自动跟进 |
| 推荐场景 | 内网仿真集群、要求可审计 | 对接外部生态、需要 resources/prompts |