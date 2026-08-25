# 02 · Python MCP Server 完整代码（统一合约 + 两个仿真插件）

<aside>
📐

设计要点：`sim_mcp_common/` 是**引擎无关的公共库**（合约 + 校验 + 作业状态机 + MCP 注册）；`plugins/` 里每个文件都是**纯声明**，没有任何协议代码。新增一种仿真 = 拷一份 `xxx_plugin.py` 改声明 + 一个 6 行的 `xxx_server.py`。

</aside>

## 0. requirements.txt

```
# 美一层只靠官方 SDK，不引入任何 Agent 框架
mcp[cli]>=1.12,<2
pydantic>=2.7,<3
uvicorn>=0.30
starlette>=0.37
```

<aside>
⚠️

**版本坑（必看）**：`pip install mcp` 现在会装到 **SDK v2**（API 改成了 `from mcp.server import MCPServer`）。本文代码基于更成熟、资料最多的 **v1.x 的 FastMCP**，所以**必须钉住上限** `mcp>=1.12,<2`。两者协议完全一致，Java 客户端不需要改一行；以后想升 v2 只改这一层。

</aside>

---

## 1. `sim_mcp_common/contracts.py` —— 合约数据模型

```python
"""统一《仿真插件合约》数据模型。

这里的每一个字段都会最终变成大模型可读的信息，所以 description / unit /
ask_hint 请当作「写给同事看的说明书」来写，不要写 a/b/c。
"""
from __future__ import annotations

from typing import Any, Literal, Optional

from pydantic import BaseModel, Field

ParamType = Literal["string", "number", "integer", "boolean", "enum", "file", "object"]
ParamGroup = Literal["basic", "advanced", "expert"]
JobState = Literal["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"]

class ParamSpec(BaseModel):
    """一个仿真参数的完整描述 —— 让大模型「知道要哪些参数」的唯一真源。"""

    name: str
    title: str
    description: str
    type: ParamType = "string"
    required: bool = False
    group: ParamGroup = "basic"
    unit: Optional[str] = None                      # 单位，比如 um / nm / V / mJ/cm^2
    default: Optional[Any] = None
    options: Optional[list[Any]] = None             # enum 候选项
    minimum: Optional[float] = None
    maximum: Optional[float] = None
    example: Optional[Any] = None
    required_if: Optional[str] = None               # 条件必填："mesh_mode == 'manual'"
    ask_hint: Optional[str] = None                  # 缺失时向用户提问的话术
    aliases: list[str] = Field(default_factory=list)  # 用户口语说法，帮模型映射
    internal: bool = False                          # 调试参数，不向用户展示

class PrerequisiteSpec(BaseModel):
    """前置条件：可能是「必须先跑另一个仿真」，也可能是「必须提供某个文件」。"""

    key: str                                        # 传参时的键名，upstream_artifacts[key]
    description: str
    kind: Literal["artifact", "job", "manual"] = "artifact"
    required: bool = True
    produced_by_server: Optional[str] = None        # 由哪个 MCP Server 产出
    produced_by_workflow: Optional[str] = None      # 由哪个工作流产出
    produced_artifact: Optional[str] = None         # 对应产物名
    auto_resolvable: bool = True                    # 引擎可否自动编排前置任务
    ask_hint: Optional[str] = None

class StepSpec(BaseModel):
    id: str
    name: str
    description: str
    engine_hint: Optional[str] = None               # 真实对应的仿真工具/脚本
    mock_seconds: float = 2.0
    produces: list[str] = Field(default_factory=list)

class PresetSpec(BaseModel):
    id: str
    name: str
    description: str
    params: dict[str, Any]

class ArtifactSpec(BaseModel):
    name: str
    description: str
    media_type: str = "text/plain"

class WorkflowSpec(BaseModel):
    id: str
    name: str
    summary: str
    when_to_use: str                                # 什么情况下选它（路由关键！）
    steps: list[StepSpec]
    params: list[ParamSpec]
    typical_minutes: int = 20
    cost_level: Literal["low", "medium", "high"] = "medium"
    prerequisites: list[PrerequisiteSpec] = Field(default_factory=list)
    presets: list[PresetSpec] = Field(default_factory=list)
    artifacts: list[ArtifactSpec] = Field(default_factory=list)
    result_keys: list[str] = Field(default_factory=list)
    sop_markdown: str = ""

    def param(self, name: str) -> Optional[ParamSpec]:
        for p in self.params:
            if p.name == name or name in p.aliases:
                return p
        return None

class SimPlugin(BaseModel):
    id: str                                         # 引擎侧工具前缀，如 coventor
    name: str
    vendor: str
    description: str
    instructions: str = ""                          # 会进 initialize 结果，引擎拼进 system prompt
    workflows: list[WorkflowSpec]

    def workflow(self, wid: str) -> Optional[WorkflowSpec]:
        for w in self.workflows:
            if w.id == wid:
                return w
        return None

# ---------- 工具返回结构（全部结构化，方便 Java 侧直接取字段） ----------

class WorkflowBrief(BaseModel):
    id: str
    name: str
    summary: str
    when_to_use: str
    typical_minutes: int
    cost_level: str
    required_params: list[str]
    artifacts: list[str]
    prerequisites: list[str]                        # 已格式化成人读友好的一行

class CapabilityCard(BaseModel):
    plugin_id: str
    name: str
    vendor: str
    description: str
    usage_notes: str
    workflows: list[WorkflowBrief]
    write_tools: list[str]                          # 会产生副作用/花钱的工具，引擎据此加审批

class Issue(BaseModel):
    code: Literal[
        "MISSING_REQUIRED", "TYPE_ERROR", "OUT_OF_RANGE",
        "BAD_ENUM", "UNKNOWN_PARAM", "PRECONDITION_MISSING",
    ]
    message: str
    param: Optional[str] = None
    question: Optional[str] = None                  # 直接可以拿去问用户的话
    expected: Optional[str] = None
    severity: Literal["error", "warning"] = "error"

class ValidateResult(BaseModel):
    ok: bool
    plugin_id: str
    workflow_id: str
    next_action: Literal["ASK_USER", "RUN_PREREQUISITE", "READY_TO_SUBMIT"]
    normalized_params: dict[str, Any]
    issues: list[Issue] = Field(default_factory=list)
    missing_required: list[str] = Field(default_factory=list)
    unresolved_prerequisites: list[PrerequisiteSpec] = Field(default_factory=list)
    suggested_prerequisite_plan: list[str] = Field(default_factory=list)
    ask_user_message: Optional[str] = None
    ask_user_schema: Optional[dict[str, Any]] = None  # 前端可直接渲染的 JSON Schema 表单

class ArtifactRef(BaseModel):
    name: str
    path: str
    size_bytes: int = 0
    media_type: str = "text/plain"

class JobRef(BaseModel):
    job_id: str
    plugin_id: str
    workflow_id: str
    status: JobState
    submitted_at: str
    idempotent_hit: bool = False                    # 命中幂等，未重复提交
    message: str = ""
    poll_hint: str = "请用 get_job_status(job_id) 轮询，建议间隔 5~15 秒"

class JobStatus(BaseModel):
    job_id: str
    plugin_id: str
    workflow_id: str
    status: JobState
    progress: float = 0.0
    current_step: Optional[str] = None
    finished_steps: list[str] = Field(default_factory=list)
    outputs: dict[str, Any] = Field(default_factory=dict)
    artifacts: list[ArtifactRef] = Field(default_factory=list)
    logs_tail: str = ""
    error_code: Optional[str] = None
    error_message: Optional[str] = None
    elapsed_seconds: float = 0.0
    created_at: str = ""
    updated_at: str = ""
```

---

## 2. `sim_mcp_common/validation.py` —— 校验引擎 + 自动生成补参表单

<aside>
⭐

这个文件直接回答你的疑问：「用户没提供完整参数就咨询用户」—— 不靠提示词神奇，靠服务端返回 `next_action=ASK_USER` 与一份现成的表单 Schema。

</aside>

```python
"""参数校验引擎：类型强转 + 范围/枚举 + 条件必填 + 前置依赖，
并且把「缺什么」直接翻译成 JSON Schema 表单与中文提问话术。"""
from __future__ import annotations

import re
from typing import Any, Optional

from .contracts import (
    ArtifactSpec, Issue, ParamSpec, PrerequisiteSpec, SimPlugin,
    ValidateResult, WorkflowSpec,
)

_ATOM = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(==|!=|>=|<=|>|<)\s*(.+?)\s*$")

def _literal(raw: str) -> Any:
    raw = raw.strip()
    if len(raw) >= 2 and raw[0] in "'\"" and raw[-1] == raw[0]:
        return raw[1:-1]
    low = raw.lower()
    if low in ("true", "false"):
        return low == "true"
    if low in ("none", "null"):
        return None
    try:
        return int(raw)
    except ValueError:
        pass
    try:
        return float(raw)
    except ValueError:
        return raw

def _eval_atom(expr: str, params: dict[str, Any]) -> bool:
    m = _ATOM.match(expr)
    if not m:
        return False
    name, op, rhs_raw = m.group(1), m.group(2), m.group(3)
    left, right = params.get(name), _literal(rhs_raw)
    try:
        if op == "==":
            return left == right
        if op == "!=":
            return left != right
        if left is None:
            return False
        if op == ">":
            return float(left) > float(right)
        if op == "<":
            return float(left) < float(right)
        if op == ">=":
            return float(left) >= float(right)
        if op == "<=":
            return float(left) <= float(right)
    except (TypeError, ValueError):
        return False
    return False

def eval_condition(expr: Optional[str], params: dict[str, Any]) -> bool:
    """安全的条件求值（不用 eval），支持 and / or 组合。"""
    if not expr:
        return True
    for or_part in re.split(r"\s+or\s+", expr):
        if all(_eval_atom(a, params) for a in re.split(r"\s+and\s+", or_part)):
            return True
    return False

def _coerce(spec: ParamSpec, value: Any) -> tuple[Any, Optional[Issue]]:
    """大模型经常把数字写成字符串，这里做宽容强转。"""
    try:
        if spec.type == "integer":
            value = int(float(value))
        elif spec.type == "number":
            value = float(value)
        elif spec.type == "boolean":
            if isinstance(value, str):
                value = value.strip().lower() in ("true", "1", "yes", "y", "是")
            else:
                value = bool(value)
        elif spec.type in ("string", "file", "enum"):
            value = str(value)
    except (TypeError, ValueError):
        return value, Issue(
            code="TYPE_ERROR", param=spec.name, expected=spec.type,
            message="参数 %s 应为 %s，实际收到：%r" % (spec.name, spec.type, value),
            question="%s（%s）的值似乎不对，能再确认一下吗？" % (spec.title, spec.name),
        )

    if spec.type == "enum" and spec.options and value not in spec.options:
        return value, Issue(
            code="BAD_ENUM", param=spec.name, expected="/".join(map(str, spec.options)),
            message="参数 %s 必须是 %s 之一" % (spec.name, spec.options),
            question="%s 请在这几项里选：%s" % (spec.title, "、".join(map(str, spec.options))),
        )
    if spec.minimum is not None and isinstance(value, (int, float)) and value < spec.minimum:
        return value, Issue(
            code="OUT_OF_RANGE", param=spec.name, expected=">= %s" % spec.minimum,
            message="%s 不能小于 %s%s" % (spec.name, spec.minimum, spec.unit or ""),
            question="%s 目前填的是 %s，超出合法范围（≥ %s），请重新确认。"
                     % (spec.title, value, spec.minimum),
        )
    if spec.maximum is not None and isinstance(value, (int, float)) and value > spec.maximum:
        return value, Issue(
            code="OUT_OF_RANGE", param=spec.name, expected="<= %s" % spec.maximum,
            message="%s 不能大于 %s%s" % (spec.name, spec.maximum, spec.unit or ""),
            question="%s 目前填的是 %s，超出合法范围（≤ %s），请重新确认。"
                     % (spec.title, value, spec.maximum),
        )
    return value, None

def _json_type(t: str) -> str:
    return {"integer": "integer", "number": "number", "boolean": "boolean"}.get(t, "string")

def build_form_schema(wf: WorkflowSpec, names: list[str], title: str = "") -> dict[str, Any]:
    """把缺失参数翻译成前端可直接渲染的 JSON Schema。"""
    props: dict[str, Any] = {}
    required: list[str] = []
    for name in names:
        spec = wf.param(name)
        if spec is None or spec.internal:
            continue
        node: dict[str, Any] = {
            "type": _json_type(spec.type),
            "title": spec.title + ("（%s）" % spec.unit if spec.unit else ""),
            "description": spec.description,
        }
        if spec.options:
            node["enum"] = spec.options
        if spec.default is not None:
            node["default"] = spec.default
        if spec.minimum is not None:
            node["minimum"] = spec.minimum
        if spec.maximum is not None:
            node["maximum"] = spec.maximum
        if spec.example is not None:
            node["examples"] = [spec.example]
        props[name] = node
        if spec.required:
            required.append(name)
    return {
        "type": "object",
        "title": title or ("补充 %s 所需参数" % wf.name),
        "properties": props,
        "required": required,
    }

def validate(
    plugin: SimPlugin,
    wf: WorkflowSpec,
    params: dict[str, Any],
    upstream_artifacts: Optional[dict[str, Any]] = None,
    preset_id: Optional[str] = None,
) -> ValidateResult:
    upstream = dict(upstream_artifacts or {})
    merged: dict[str, Any] = {}

    # 1) 预设打底 → 2) 默认值打底 → 3) 用户传入覆盖
    if preset_id:
        preset = next((p for p in wf.presets if p.id == preset_id), None)
        if preset:
            merged.update(preset.params)
    for spec in wf.params:
        if spec.default is not None and spec.name not in merged:
            merged[spec.name] = spec.default
    for raw_key, value in (params or {}).items():
        spec = wf.param(raw_key)
        merged[spec.name if spec else raw_key] = value

    issues: list[Issue] = []

    # 未知参数只告警，不阻断（模型偶尔会多给）
    known = {p.name for p in wf.params}
    for key in list(merged.keys()):
        if key not in known:
            issues.append(Issue(
                code="UNKNOWN_PARAM", param=key, severity="warning",
                message="参数 %s 不属于工作流 %s，已忽略。可用 list_parameters 查看合法参数。"
                        % (key, wf.id),
            ))
            merged.pop(key)

    # 类型/范围/枚举
    for spec in wf.params:
        if spec.name in merged and merged[spec.name] is not None:
            coerced, issue = _coerce(spec, merged[spec.name])
            merged[spec.name] = coerced
            if issue:
                issues.append(issue)

    # 必填 + 条件必填
    missing: list[str] = []
    for spec in wf.params:
        need = spec.required or (spec.required_if and eval_condition(spec.required_if, merged))
        if need and merged.get(spec.name) in (None, ""):
            missing.append(spec.name)
            issues.append(Issue(
                code="MISSING_REQUIRED", param=spec.name,
                expected="%s%s" % (spec.type, " in " + str(spec.unit) if spec.unit else ""),
                message="缺少必填参数 %s（%s）" % (spec.name, spec.title),
                question=spec.ask_hint or ("请提供%s%s" % (spec.title, "（单位：%s）" % spec.unit if spec.unit else "")),
            ))

    # 前置依赖
    unresolved: list[PrerequisiteSpec] = []
    plan: list[str] = []
    for pre in wf.prerequisites:
        if not pre.required:
            continue
        if not upstream.get(pre.key):
            unresolved.append(pre)
            if pre.kind in ("artifact", "job") and pre.produced_by_workflow:
                plan.append("%s.%s" % (pre.produced_by_server or plugin.id, pre.produced_by_workflow))
            issues.append(Issue(
                code="PRECONDITION_MISSING", param=pre.key,
                expected="upstream_artifacts['%s']" % pre.key,
                message=("前置条件未满足：%s。需要先执行 %s.%s 获得产物 %s，"
                         "然后通过 upstream_artifacts['%s'] 传入。")
                        % (pre.description, pre.produced_by_server or plugin.id,
                           pre.produced_by_workflow, pre.produced_artifact, pre.key),
                question=pre.ask_hint or ("%s 还没有。是要我先自动跑一遗 %s 吗？或者你直接给我已有的文件路径？"
                                          % (pre.description, pre.produced_by_workflow)),
            ))

    errors = [i for i in issues if i.severity == "error"]
    if unresolved:
        next_action = "RUN_PREREQUISITE"
    elif errors:
        next_action = "ASK_USER"
    else:
        next_action = "READY_TO_SUBMIT"

    ask_names = missing + [i.param for i in errors
                           if i.code in ("TYPE_ERROR", "OUT_OF_RANGE", "BAD_ENUM") and i.param]
    ask_schema = build_form_schema(wf, list(dict.fromkeys(ask_names))) if ask_names else None
    ask_msg = None
    if errors:
        ask_msg = "为了跑「%s」，还需要确认以下信息：\n" % wf.name + "\n".join(
            "• " + (i.question or i.message) for i in errors[:6]
        )

    return ValidateResult(
        ok=not errors,
        plugin_id=plugin.id,
        workflow_id=wf.id,
        next_action=next_action,
        normalized_params=merged,
        issues=issues,
        missing_required=missing,
        unresolved_prerequisites=unresolved,
        suggested_prerequisite_plan=plan,
        ask_user_message=ask_msg,
        ask_user_schema=ask_schema,
    )
```

---

## 3. `sim_mcp_common/jobstore.py` —— 作业状态机（SQLite + 线程池）

```python
"""作业存储与异步执行。用 SQLite 是为了插件自己就能重启恢复，
且不依赖任何业务库（业务落库是 Java 引擎的责任）。"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Optional

_DDL = """
CREATE TABLE IF NOT EXISTS jobs (
  job_id           TEXT PRIMARY KEY,
  plugin_id        TEXT NOT NULL,
  workflow_id      TEXT NOT NULL,
  status           TEXT NOT NULL,
  progress         REAL NOT NULL DEFAULT 0,
  current_step     TEXT,
  finished_steps   TEXT DEFAULT '[]',
  params_json      TEXT NOT NULL,
  upstream_json    TEXT DEFAULT '{}',
  outputs_json     TEXT DEFAULT '{}',
  artifacts_json   TEXT DEFAULT '[]',
  logs             TEXT DEFAULT '',
  error_code       TEXT,
  error_message    TEXT,
  idempotency_key  TEXT,
  requested_by     TEXT,
  external_ref     TEXT,
  cancel_requested INTEGER DEFAULT 0,
  created_at       REAL,
  updated_at       REAL,
  started_at       REAL,
  finished_at      REAL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_idem
  ON jobs(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_jobs_status ON jobs(status);
"""

TERMINAL = ("SUCCEEDED", "FAILED", "CANCELED")

class JobStore:
    def __init__(self, db_path: str, workspace: str, max_workers: int = 4) -> None:
        self.db_path = db_path
        self.workspace = workspace
        os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
        os.makedirs(workspace, exist_ok=True)
        self._lock = threading.RLock()
        self._pool = ThreadPoolExecutor(max_workers=max_workers,
                                        thread_name_prefix="sim-job")
        with self._conn() as c:
            c.executescript(_DDL)

    # ---------- 基础 ----------
    def _conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=15)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        return conn

    def workdir(self, job_id: str) -> str:
        d = os.path.join(self.workspace, job_id)
        os.makedirs(d, exist_ok=True)
        return d

    # ---------- 写 ----------
    def create(self, plugin_id: str, workflow_id: str, params: dict[str, Any],
               upstream: dict[str, Any], idempotency_key: Optional[str] = None,
               requested_by: Optional[str] = None,
               external_ref: Optional[str] = None) -> tuple[dict[str, Any], bool]:
        """返回 (job行, 是否幂等命中)。幂等命中时直接返回已有作业。"""
        with self._lock:
            if idempotency_key:
                existing = self.find_by_idem(idempotency_key)
                if existing:
                    return existing, True
            now = time.time()
            job_id = "%s-%s" % (plugin_id, uuid.uuid4().hex[:12])
            with self._conn() as c:
                c.execute(
                    "INSERT INTO jobs(job_id,plugin_id,workflow_id,status,progress,"
                    "params_json,upstream_json,idempotency_key,requested_by,external_ref,"
                    "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (job_id, plugin_id, workflow_id, "QUEUED", 0.0,
                     json.dumps(params, ensure_ascii=False),
                     json.dumps(upstream, ensure_ascii=False),
                     idempotency_key, requested_by, external_ref, now, now),
                )
            return self.get(job_id), False

    def update(self, job_id: str, **fields: Any) -> None:
        if not fields:
            return
        fields["updated_at"] = time.time()
        cols = ", ".join("%s=?" % k for k in fields)
        with self._lock, self._conn() as c:
            c.execute("UPDATE jobs SET %s WHERE job_id=?" % cols,
                      list(fields.values()) + [job_id])

    def append_log(self, job_id: str, line: str) -> None:
        stamped = "[%s] %s" % (time.strftime("%H:%M:%S"), line)
        with self._lock, self._conn() as c:
            c.execute("UPDATE jobs SET logs = COALESCE(logs,'') || ? || char(10),"
                      " updated_at=? WHERE job_id=?", (stamped, time.time(), job_id))

    def request_cancel(self, job_id: str) -> None:
        self.update(job_id, cancel_requested=1)

    # ---------- 读 ----------
    def get(self, job_id: str) -> Optional[dict[str, Any]]:
        with self._conn() as c:
            row = c.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        return dict(row) if row else None

    def find_by_idem(self, key: str) -> Optional[dict[str, Any]]:
        with self._conn() as c:
            row = c.execute("SELECT * FROM jobs WHERE idempotency_key=?", (key,)).fetchone()
        return dict(row) if row else None

    def is_cancel_requested(self, job_id: str) -> bool:
        row = self.get(job_id)
        return bool(row and row.get("cancel_requested"))

    # ---------- 执行 ----------
    def submit(self, job_id: str, fn: Callable[[str], None]) -> None:
        self._pool.submit(self._guarded, job_id, fn)

    def _guarded(self, job_id: str, fn: Callable[[str], None]) -> None:
        try:
            fn(job_id)
        except BaseException as exc:                     # 绝不让线程默默死掉
            self.append_log(job_id, "FATAL %s: %s" % (type(exc).__name__, exc))
            self.update(job_id, status="FAILED", error_code="INTERNAL_ERROR",
                        error_message=str(exc), finished_at=time.time())
```

---

## 4. `sim_mcp_common/mock_runner.py` —— mock 仿真引擎（**这是以后唯一要改的文件**）

```python
"""mock 仿真执行器：真实分步、真实耗时、真实写产物文件，
并用简化解析式算出看着像真的结果，方便验证整条链路。

接真实仿真时：把 _simulate_step 里的 time.sleep 换成
    subprocess.run(["python", "/opt/sim/coventor_modal.py", "--config", cfg_path], ...)
即可，其余代码一行不动。
"""
from __future__ import annotations

import json
import math
import os
import time
from typing import Any, Callable, Iterator

from .contracts import StepSpec, WorkflowSpec

EPS0 = 8.854e-12
MATERIAL = {                      # E(Pa), rho(kg/m^3)
    "Si":   (169e9, 2330.0),
    "SiO2": (70e9, 2200.0),
    "SiN":  (250e9, 3100.0),
    "Al":   (70e9, 2700.0),
    "Poly": (160e9, 2320.0),
}
CANTILEVER_RATIO = [1.0, 6.267, 17.55, 34.39, 56.84, 85.0]

class MockFailure(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code

def _write(workdir: str, name: str, content: str) -> dict[str, Any]:
    path = os.path.join(workdir, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return {"name": name, "path": path, "size_bytes": os.path.getsize(path),
            "media_type": "text/csv" if name.endswith(".csv") else "text/plain"}

# ---------------- 简化物理（让 mock 结果可信） ----------------

def _litho_results(params: dict[str, Any]) -> dict[str, Any]:
    dose = float(params.get("dose_mj_cm2", 30.0))
    focus = float(params.get("focus_nm", 0.0))
    na = float(params.get("na", 1.35))
    wl = float(params.get("wavelength_nm", 193.0))
    k1 = 0.35
    min_cd = k1 * wl / na
    cd = min_cd * (1 + 0.015 * (dose - 30.0) - 2.0e-5 * focus * focus)
    return {
        "cd_nm": round(cd, 2),
        "min_resolvable_cd_nm": round(min_cd, 2),
        "cd_uniformity_3sigma_nm": round(abs(cd) * 0.021 + 0.4, 3),
        "exposure_latitude_pct": round(max(2.0, 12.0 - abs(dose - 30.0) * 0.5), 2),
        "depth_of_focus_nm": round(max(20.0, 140.0 - abs(focus) * 0.35), 1),
        "sidewall_angle_deg": round(88.5 - abs(focus) * 0.01, 2),
    }

def _modal_results(params: dict[str, Any]) -> dict[str, Any]:
    L = float(params["beam_length_um"]) * 1e-6
    w = float(params["beam_width_um"]) * 1e-6
    t = float(params["thickness_um"]) * 1e-6
    E, rho = MATERIAL.get(str(params.get("material", "Si")), MATERIAL["Si"])
    n = int(params.get("num_modes", 5))
    f0 = 0.1615 * (t / (L * L)) * math.sqrt(E / rho)          # 悬臂梁一阶模态
    k = E * w * (t ** 3) / (4 * (L ** 3))                      # 弹性常数
    m_eff = 0.24 * rho * L * w * t
    modes = [round(f0 * r, 2) for r in CANTILEVER_RATIO[:max(1, min(n, 6))]]
    return {
        "first_mode_hz": round(f0, 2),
        "first_mode_khz": round(f0 / 1e3, 3),
        "modes_hz": modes,
        "spring_constant_n_per_m": round(k, 4),
        "effective_mass_kg": m_eff,
        "quality_factor": round(1200 / (1 + 8 * float(params.get("damping_ratio", 0.001))), 1),
    }

def _pullin_results(params: dict[str, Any], upstream_outputs: dict[str, Any]) -> dict[str, Any]:
    g0 = float(params["gap_um"]) * 1e-6
    area = float(params.get("electrode_area_um2", 10000.0)) * 1e-12
    k = float(upstream_outputs.get("spring_constant_n_per_m", params.get("spring_constant_n_per_m", 12.0)))
    v_pi = math.sqrt(8 * k * (g0 ** 3) / (27 * EPS0 * area))
    return {
        "pull_in_voltage_v": round(v_pi, 3),
        "pull_in_gap_um": round(float(params["gap_um"]) * 2.0 / 3.0, 4),
        "travel_range_um": round(float(params["gap_um"]) / 3.0, 4),
        "used_spring_constant_n_per_m": k,
    }

_RESULT_FN = {
    "mask_opc": lambda p, u: {
        "epe_max_nm": round(3.2 - 0.15 * int(p.get("iterations", 8)), 3),
        "mrc_violations": max(0, 6 - int(p.get("iterations", 8))),
        "iterations_used": int(p.get("iterations", 8)),
    },
    "pattern_transfer": lambda p, u: _litho_results(p),
    "mems_resonator_modal": lambda p, u: _modal_results(p),
    "mems_pull_in": lambda p, u: _pullin_results(p, u),
}

# ---------------- 执行器 ----------------

def _artifact_content(wf: WorkflowSpec, name: str, params: dict[str, Any],
                      results: dict[str, Any]) -> str:
    if name.endswith(".csv") or name == "modes_csv":
        rows = ["mode_index,frequency_hz"]
        for i, f in enumerate(results.get("modes_hz", []), 1):
            rows.append("%d,%s" % (i, f))
        return "\n".join(rows) + "\n"
    head = "# %s 仿真报告 (MOCK)\n\n## 输入参数\n" % wf.name
    body = json.dumps(params, ensure_ascii=False, indent=2)
    tail = "\n\n## 关键结果\n" + json.dumps(results, ensure_ascii=False, indent=2)
    return head + body + tail + "\n"

def _simulate_step(wf: WorkflowSpec, step: StepSpec, params: dict[str, Any],
                   upstream: dict[str, Any], workdir: str, state: dict[str, Any],
                   log: Callable[[str], None]) -> None:
    speed = float(params.get("mock_speed", 1.0)) or 1.0
    log("start step=%s (%s) engine=%s" % (step.id, step.name, step.engine_hint or "mock"))

    # 故障注入：方便你测试 Agent 的失败恢复能力
    if str(params.get("mock_fail_step", "")) == step.id:
        raise MockFailure("SOLVER_DIVERGED",
                          "步骤 %s 求解发散（故障注入）：请减小网格或降低步长" % step.id)

    time.sleep(max(0.05, step.mock_seconds / speed))

    # 最后一步统一算结果（也可以每步都算，看你真实仿真的粒度）
    if step is wf.steps[-1]:
        fn = _RESULT_FN.get(wf.id)
        results = fn(params, state.get("upstream_outputs", {})) if fn else {}
        state["outputs"].update(results)

    for artifact_name in step.produces:
        spec = next((a for a in wf.artifacts if a.name == artifact_name), None)
        filename = {
            "resist_profile_file": "resist_profile.prf",
            "opc_mask_file": "opc_mask.oas",
            "cd_report": "cd_report.txt",
            "mrc_report": "mrc_report.txt",
            "modal_report": "modal_report.txt",
            "modes_csv": "modes.csv",
            "pullin_report": "pullin_report.txt",
        }.get(artifact_name, artifact_name + ".txt")
        meta = _write(workdir, filename,
                      _artifact_content(wf, filename, params, state["outputs"]))
        meta["name"] = artifact_name
        if spec:
            meta["media_type"] = spec.media_type
        state["artifacts"] = [a for a in state["artifacts"] if a["name"] != artifact_name]
        state["artifacts"].append(meta)
        state["outputs"][artifact_name] = meta["path"]      # 下游仿真靠这个拿文件
        log("produced artifact %s -> %s" % (artifact_name, meta["path"]))

    log("done step=%s" % step.id)

def run_workflow(wf: WorkflowSpec, params: dict[str, Any], upstream: dict[str, Any],
                 workdir: str, log: Callable[[str], None]
                 ) -> Iterator[tuple[int, StepSpec, dict[str, Any]]]:
    """生成器：每完成一步 yield 一次，由调用方负责写库/上报进度/判断取消。"""
    state: dict[str, Any] = {"outputs": {}, "artifacts": [], "upstream_outputs": {}}
    for key, value in (upstream or {}).items():
        state["outputs"]["upstream_" + key] = value
        if isinstance(value, (int, float)):
            state["upstream_outputs"][key] = value
    log("workflow=%s steps=%d upstream=%s" % (wf.id, len(wf.steps), list(upstream or {})))
    for idx, step in enumerate(wf.steps, start=1):
        _simulate_step(wf, step, params, upstream, workdir, state, log)
        yield idx, step, state
```

---

## 5. `sim_mcp_common/server_factory.py` —— 把合约自动变成 MCP Server

<aside>
🔑

工具的 **docstring 就是给大模型看的说明书**（FastMCP 会把它当作 tool description）。这里每句话都是刻意写的，包括「必须先调 validate_params」这种流程约束。

</aside>

```python
"""把一份 SimPlugin 声明，自动注册成符合《仿真插件合约》的 MCP Server。"""
from __future__ import annotations

import asyncio
import json
import os
import time
from typing import Annotated, Any, Optional

from mcp.server.fastmcp import Context, FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from pydantic import Field
from starlette.requests import Request
from starlette.responses import JSONResponse

from .contracts import (
    ArtifactRef, CapabilityCard, JobRef, JobStatus, PresetSpec,
    SimPlugin, ValidateResult, WorkflowBrief, WorkflowSpec,
)
from .jobstore import TERMINAL, JobStore
from .mock_runner import MockFailure, run_workflow
from .validation import validate

def _iso(ts: Optional[float]) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ts)) if ts else ""

def _brief(wf: WorkflowSpec, plugin_id: str) -> WorkflowBrief:
    return WorkflowBrief(
        id=wf.id, name=wf.name, summary=wf.summary, when_to_use=wf.when_to_use,
        typical_minutes=wf.typical_minutes, cost_level=wf.cost_level,
        required_params=[p.name for p in wf.params if p.required],
        artifacts=[a.name for a in wf.artifacts],
        prerequisites=[
            "%s <- %s.%s 的产物 %s (%s)" % (
                pre.key, pre.produced_by_server or plugin_id, pre.produced_by_workflow,
                pre.produced_artifact, "必需" if pre.required else "可选")
            for pre in wf.prerequisites
        ],
    )

def build_server(plugin: SimPlugin, host: str = "0.0.0.0", port: int = 9100,
                 workspace: str = "./_workspace",
                 db_path: Optional[str] = None) -> tuple[FastMCP, JobStore]:
    store = JobStore(db_path or "./_data/%s_jobs.db" % plugin.id, workspace)

    mcp = FastMCP(
        name="%s-mcp" % plugin.id,
        instructions=plugin.instructions,   # 会随 initialize 返回给客户端
        host=host,
        port=port,
        streamable_http_path="/mcp",
        json_response=False,                # False = 允许 SSE，才能推进度通知
        stateless_http=False,
    )

    def _wf(workflow_id: str) -> WorkflowSpec:
        wf = plugin.workflow(workflow_id)
        if wf is None:
            raise ToolError("未知 workflow_id=%s，可用：%s"
                            % (workflow_id, [w.id for w in plugin.workflows]))
        return wf

    def _status(job: dict[str, Any], logs_tail_lines: int = 30) -> JobStatus:
        logs = (job.get("logs") or "").strip().splitlines()
        started, finished = job.get("started_at"), job.get("finished_at")
        elapsed = (finished or time.time()) - started if started else 0.0
        return JobStatus(
            job_id=job["job_id"], plugin_id=job["plugin_id"], workflow_id=job["workflow_id"],
            status=job["status"], progress=job.get("progress") or 0.0,
            current_step=job.get("current_step"),
            finished_steps=json.loads(job.get("finished_steps") or "[]"),
            outputs=json.loads(job.get("outputs_json") or "{}"),
            artifacts=[ArtifactRef(**a) for a in json.loads(job.get("artifacts_json") or "[]")],
            logs_tail="\n".join(logs[-logs_tail_lines:]),
            error_code=job.get("error_code"), error_message=job.get("error_message"),
            elapsed_seconds=round(elapsed, 1),
            created_at=_iso(job.get("created_at")), updated_at=_iso(job.get("updated_at")),
        )

    # ---------------- 后台执行体 ----------------
    def _execute(job_id: str) -> None:
        job = store.get(job_id)
        wf = _wf(job["workflow_id"])
        params = json.loads(job["params_json"])
        upstream = json.loads(job.get("upstream_json") or "{}")
        workdir = store.workdir(job_id)
        store.update(job_id, status="RUNNING", started_at=time.time(), progress=1.0)

        def log(line: str) -> None:
            store.append_log(job_id, line)

        total, done = len(wf.steps), []
        try:
            for idx, step, state in run_workflow(wf, params, upstream, workdir, log):
                if store.is_cancel_requested(job_id):
                    store.update(job_id, status="CANCELED", finished_at=time.time(),
                                 error_code="CANCELED", error_message="用户取消")
                    log("canceled by user")
                    return
                done.append(step.id)
                store.update(
                    job_id,
                    progress=round(idx * 100.0 / total, 1),
                    current_step="%s (%s)" % (step.id, step.name),
                    finished_steps=json.dumps(done),
                    outputs_json=json.dumps(state["outputs"], ensure_ascii=False),
                    artifacts_json=json.dumps(state["artifacts"], ensure_ascii=False),
                )
            store.update(job_id, status="SUCCEEDED", progress=100.0,
                         current_step=None, finished_at=time.time())
            log("workflow succeeded")
        except MockFailure as exc:
            store.update(job_id, status="FAILED", error_code=exc.code,
                         error_message=str(exc), finished_at=time.time())
            log("FAILED %s: %s" % (exc.code, exc))

    # ================= 合约工具 =================

    @mcp.tool()
    def describe_capability() -> CapabilityCard:
        """返回本仿真插件的能力卡片（能干什么、适用场景、产物、前置依赖、写操作清单）。

        引擎启动时会调用一次并缓存，用于生成全局能力图谱。你（模型）平时不需要重复调用。
        """
        return CapabilityCard(
            plugin_id=plugin.id, name=plugin.name, vendor=plugin.vendor,
            description=plugin.description, usage_notes=plugin.instructions,
            workflows=[_brief(w, plugin.id) for w in plugin.workflows],
            write_tools=["submit_job", "run_job_sync", "cancel_job"],
        )

    @mcp.tool()
    def list_workflows() -> list[WorkflowBrief]:
        """列出本插件支持的所有仿真工作流及其适用场景。不确定该跑哪个时先调这个。"""
        return [_brief(w, plugin.id) for w in plugin.workflows]

    @mcp.tool()
    def get_workflow(
        workflow_id: Annotated[str, Field(description="工作流 ID，来自 list_workflows")],
    ) -> dict[str, Any]:
        """获取某个仿真工作流的完整 SOP：执行步骤、**前置依赖**、全部参数、产物、预估耗时。

        重要：如果返回的 prerequisites 非空，说明必须先完成其他仿真并把产物通过
        upstream_artifacts 传入，否则 submit_job 会直接失败。
        """
        wf = _wf(workflow_id)
        data = wf.model_dump()
        data["plugin_id"] = plugin.id
        data["how_to_call"] = (
            "1) list_parameters 看参数 → 2) validate_params 校验（必做） → "
            "3) 若 next_action=ASK_USER 先问用户 → 4) submit_job → 5) get_job_status 轮询"
        )
        return data

    @mcp.tool()
    def list_parameters(
        workflow_id: str,
        group: Annotated[Optional[str], Field(description="basic|advanced|expert，缺省全部")] = None,
    ) -> dict[str, Any]:
        """列出指定工作流的参数目录：必填项、单位、取值范围、默认值、条件必填规则。

        参数很多时建议先只看 group='basic'，高级参数保持默认值即可。
        """
        wf = _wf(workflow_id)
        items = [p.model_dump(exclude_none=True) for p in wf.params
                 if not p.internal and (group is None or p.group == group)]
        return {
            "plugin_id": plugin.id, "workflow_id": wf.id, "total": len(items),
            "required": [p.name for p in wf.params if p.required],
            "parameters": items,
            "tip": "只需向用户询问 required=true 且无默认值的参数；其余用默认值。",
        }

    @mcp.tool()
    def list_presets(workflow_id: str) -> list[PresetSpec]:
        """列出典型工况预设。用户说“跑个标准的/常规参数”时，优先用 preset_id 而不是逐个询问。"""
        return _wf(workflow_id).presets

    @mcp.tool()
    def validate_params(
        workflow_id: str,
        params: Annotated[dict[str, Any], Field(description="已知参数，可以不完整")],
        upstream_artifacts: Annotated[Optional[dict[str, Any]],
                                      Field(description="前置仿真产物，如 {'resist_profile_file': '/path/a.prf'}")] = None,
        preset_id: Optional[str] = None,
    ) -> ValidateResult:
        """**提交前必须调用**。校验参数与前置条件，并告诉你下一步应该做什么。

        返回的 next_action 含义：
        • READY_TO_SUBMIT — 参数齐了，可以 submit_job（请用 normalized_params）。
        • ASK_USER       — 参数不全/非法，请把 ask_user_message 与 ask_user_schema 交给
                            ask_user 工具去问用户，不要自己编造参数值。
        • RUN_PREREQUISITE — 缺前置仿真产物，请按 suggested_prerequisite_plan 先跑前置（推荐
                            用 submit_plan 一次性编排）。
        """
        wf = _wf(workflow_id)
        return validate(plugin, wf, params or {}, upstream_artifacts, preset_id)

    @mcp.tool()
    def submit_job(
        workflow_id: str,
        params: dict[str, Any],
        upstream_artifacts: Optional[dict[str, Any]] = None,
        preset_id: Optional[str] = None,
        idempotency_key: Annotated[Optional[str],
                                   Field(description="重复提交保护；相同键只会创建一个作业")] = None,
        external_ref: Annotated[Optional[str],
                                Field(description="业务系统任务号，便于双向对账")] = None,
        requested_by: Optional[str] = None,
    ) -> JobRef:
        """提交一个仿真作业（**异步**，立即返回 job_id，不会阻塞）。

        前置约束：必须先调 validate_params 且 next_action=READY_TO_SUBMIT；本工具会再校验一次，
        不合法会直接报错。提交成功后用 get_job_status 轮询（建议 5~15 秒一次）。
        """
        wf = _wf(workflow_id)
        result = validate(plugin, wf, params or {}, upstream_artifacts, preset_id)
        if not result.ok or result.unresolved_prerequisites:
            raise ToolError("参数或前置条件未通过校验，拒绝提交。next_action=%s；详情：%s"
                            % (result.next_action,
                               json.dumps([i.model_dump() for i in result.issues],
                                          ensure_ascii=False)))
        job, hit = store.create(plugin.id, wf.id, result.normalized_params,
                               dict(upstream_artifacts or {}), idempotency_key,
                               requested_by, external_ref)
        if not hit:
            store.submit(job["job_id"], _execute)
        return JobRef(
            job_id=job["job_id"], plugin_id=plugin.id, workflow_id=wf.id,
            status=job["status"], submitted_at=_iso(job["created_at"]), idempotent_hit=hit,
            message=("幂等命中，复用已有作业" if hit else
                     "已排队，预估耗时约 %d 分钟" % wf.typical_minutes),
        )

    @mcp.tool()
    async def run_job_sync(
        ctx: Context, workflow_id: str, params: dict[str, Any],
        upstream_artifacts: Optional[dict[str, Any]] = None,
        preset_id: Optional[str] = None, timeout_seconds: int = 180,
    ) -> JobStatus:
        """同步跑完一个**短作业**并实时上报进度（仅用于预估类/小算例，typical_minutes<=2）。

        长作业请一律用 submit_job + get_job_status，否则会超时。
        """
        ref = submit_job(workflow_id, params, upstream_artifacts, preset_id)
        deadline = time.time() + timeout_seconds
        last = -1.0
        while time.time() < deadline:
            job = store.get(ref.job_id)
            if job["progress"] != last:
                last = job["progress"]
                await ctx.report_progress(progress=float(last), total=100.0,
                                          message=job.get("current_step") or job["status"])
            if job["status"] in TERMINAL:
                await ctx.info("job %s finished: %s" % (ref.job_id, job["status"]))
                return _status(job)
            await asyncio.sleep(1.0)
        raise ToolError("同步等待超时（%ds），作业仍在运行：job_id=%s，请改用 get_job_status 轮询"
                        % (timeout_seconds, ref.job_id))

    @mcp.tool()
    def get_job_status(job_id: str, logs_tail_lines: int = 30) -> JobStatus:
        """查询作业状态、进度、当前步骤、日志尾部、输出结果与产物路径。

        status 为 SUCCEEDED 后，outputs 里就能拿到可传给下游仿真的产物路径。
        """
        job = store.get(job_id)
        if not job:
            raise ToolError("作业不存在：%s" % job_id)
        return _status(job, logs_tail_lines)

    @mcp.tool()
    def list_artifacts(job_id: str) -> list[ArtifactRef]:
        """列出作业产物（报告、曲线、轮廓文件等）。"""
        job = store.get(job_id)
        if not job:
            raise ToolError("作业不存在：%s" % job_id)
        return [ArtifactRef(**a) for a in json.loads(job.get("artifacts_json") or "[]")]

    @mcp.tool()
    def read_artifact(job_id: str, name: str, max_chars: int = 4000) -> dict[str, Any]:
        """读取产物文本内容（自动截断）。适合把仿真报告摘要给用户。"""
        for art in list_artifacts(job_id):
            if art.name == name:
                if not os.path.exists(art.path):
                    raise ToolError("产物文件不在了：%s" % art.path)
                with open(art.path, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read(max_chars + 1)
                return {"name": name, "path": art.path,
                        "truncated": len(text) > max_chars,
                        "content": text[:max_chars]}
        raise ToolError("未找到产物 %s" % name)

    @mcp.tool()
    def cancel_job(job_id: str, reason: str = "user requested") -> JobStatus:
        """请求取消作业（在下一个步骤边界生效）。"""
        job = store.get(job_id)
        if not job:
            raise ToolError("作业不存在：%s" % job_id)
        if job["status"] in TERMINAL:
            return _status(job)
        store.request_cancel(job_id)
        store.append_log(job_id, "cancel requested: %s" % reason)
        return _status(store.get(job_id))

    # ================= Resources / Prompts =================

    @mcp.resource("sim://%s/sop/{workflow_id}" % plugin.id,
                  name="%s SOP" % plugin.id,
                  description="仿真工作流的完整作业指导书（Markdown）")
    def sop_resource(workflow_id: str) -> str:
        wf = _wf(workflow_id)
        return wf.sop_markdown or ("# %s\n\n%s\n" % (wf.name, wf.summary))

    @mcp.resource("sim://%s/params/{workflow_id}" % plugin.id,
                  name="%s 参数字典" % plugin.id,
                  description="全量参数说明书")
    def params_resource(workflow_id: str) -> str:
        wf = _wf(workflow_id)
        lines = ["# %s 参数字典" % wf.name, "",
                 "| 参数 | 名称 | 类型 | 单位 | 必填 | 默认 | 范围 | 说明 |",
                 "|---|---|---|---|---|---|---|---|"]
        for p in wf.params:
            rng = "%s ~ %s" % (p.minimum, p.maximum) if p.minimum is not None else (
                "/".join(map(str, p.options)) if p.options else "-")
            lines.append("| `%s` | %s | %s | %s | %s | %s | %s | %s |" % (
                p.name, p.title, p.type, p.unit or "-", "✅" if p.required else "",
                p.default if p.default is not None else "-", rng, p.description))
        return "\n".join(lines)

    @mcp.prompt(name="how_to_run", description="引导如何正确跑一个仿真")
    def how_to_run(workflow_id: str) -> str:
        wf = _wf(workflow_id)
        return (
            "你要帮用户跑「%s」。请严格按以下步骤：\n"
            "1. get_workflow('%s') 看清步骤与前置依赖；\n"
            "2. 从用户话里抽取已知参数，调 validate_params；\n"
            "3. next_action=ASK_USER → 用 ask_user 一次性问齐缺的参数；\n"
            "4. next_action=RUN_PREREQUISITE → 用 submit_plan 把前置仿真与本仿真串成 DAG；\n"
            "5. READY_TO_SUBMIT → submit_job，然后 get_job_status 轮询；\n"
            "6. 完成后用 read_artifact 取报告，给出带单位的关键结果与工程结论。\n"
            "绝不要自己编造参数数值或文件路径。" % (wf.name, wf.id)
        )

    @mcp.custom_route("/healthz", methods=["GET"])
    async def healthz(_: Request) -> JSONResponse:
        return JSONResponse({"status": "UP", "plugin": plugin.id,
                             "workflows": [w.id for w in plugin.workflows]})

    return mcp, store
```

---

## 6. `plugins/litho_plugin.py` —— S-Litho 插件（纯声明）

```python
"""S-Litho 光刻仿真插件声明。新增仿真就拷这个文件改声明，不需要写协议代码。"""
from sim_mcp_common.contracts import (
    ArtifactSpec, ParamSpec, PresetSpec, PrerequisiteSpec, SimPlugin, StepSpec, WorkflowSpec,
)

_COMMON_OPTICS = [
    ParamSpec(name="wavelength_nm", title="曝光波长", type="number", unit="nm",
              default=193.0, options=None, group="advanced",
              description="光源波长，193 对应 ArF 浸没式，13.5 对应 EUV"),
    ParamSpec(name="na", title="数值孔径 NA", type="number", default=1.35,
              minimum=0.1, maximum=1.5, group="advanced",
              description="投影物镜数值孔径，浸没式典型值 1.35"),
    ParamSpec(name="illumination", title="照明方式", type="enum",
              options=["annular", "quadrupole", "dipole", "conventional"],
              default="annular", group="advanced",
              description="离轴照明类型，影响分辨率与工艺窗口"),
    ParamSpec(name="mock_speed", title="mock 加速倍数", type="number", default=1.0,
              group="expert", internal=True, description="仅调试：放大可缩短 mock 耗时"),
    ParamSpec(name="mock_fail_step", title="mock 故障注入步骤", type="string",
              group="expert", internal=True,
              description="仅调试：填入某个 step id 会让它报求解失败，用于测试 Agent 失败处理"),
]

MASK_OPC = WorkflowSpec(
    id="mask_opc",
    name="掩模 OPC 优化",
    summary="对给定版图做光学邻近效应修正，输出修正后的掩模与 MRC 检查报告。",
    when_to_use="用户提到 OPC、掩模修正、EPE 偏差、图形保真度不够时选这个。",
    typical_minutes=8,
    cost_level="medium",
    steps=[
        StepSpec(id="load_layout", name="载入版图", mock_seconds=1.5,
                 engine_hint="slitho_import.py", description="读取 GDS/OASIS 版图并检查图层"),
        StepSpec(id="opc_iterate", name="OPC 迭代优化", mock_seconds=4.0,
                 engine_hint="slitho_opc.py", description="根据目标 CD 迭代调整边缘分段"),
        StepSpec(id="mrc_check", name="MRC 规则检查", mock_seconds=1.5,
                 produces=["mrc_report"], description="检查掩模制造规则违例"),
        StepSpec(id="export_mask", name="导出修正掩模", mock_seconds=1.0,
                 produces=["opc_mask_file"], description="输出 OPC 后的掩模文件"),
    ],
    params=[
        ParamSpec(name="mask_file", title="版图/掩模文件", type="file", required=True,
                  example="/data/mask/resonator_v3.gds",
                  aliases=["版图", "gds", "layout"],
                  ask_hint="请提供要做 OPC 的版图文件路径（.gds 或 .oas）",
                  description="输入版图文件绝对路径"),
        ParamSpec(name="target_cd_nm", title="目标关键尺寸 CD", type="number", unit="nm",
                  required=True, minimum=5.0, maximum=500.0, example=45.0,
                  aliases=["目标CD", "线宽"],
                  ask_hint="目标关键尺寸 CD 是多少 nm？（常见 28~90）",
                  description="OPC 优化要逼近的目标关键尺寸"),
        ParamSpec(name="iterations", title="迭代次数", type="integer", default=8,
                  minimum=1, maximum=30, group="advanced",
                  description="迭代次数越多精度越高、耗时越长，常规 8 次已够"),
        ParamSpec(name="use_calibrated_model", title="使用标定模型", type="boolean",
                  default=False, group="advanced",
                  description="是否使用实测标定的光刻胶模型"),
        ParamSpec(name="model_calibration_file", title="模型标定文件", type="file",
                  group="advanced", required_if="use_calibrated_model == true",
                  ask_hint="你选了使用标定模型，请提供标定文件路径",
                  description="条件必填：仅当 use_calibrated_model=true 时需要"),
    ] + _COMMON_OPTICS,
    artifacts=[
        ArtifactSpec(name="opc_mask_file", description="OPC 修正后的掩模文件，可作为下游光刻仿真输入"),
        ArtifactSpec(name="mrc_report", description="掩模制造规则检查报告"),
    ],
    presets=[
        PresetSpec(id="standard_28nm", name="28nm 常规 OPC",
                   description="28nm 节点常规环形照明 OPC，只需再提供版图文件",
                   params={"target_cd_nm": 28.0, "iterations": 10,
                           "illumination": "annular", "na": 1.35}),
    ],
    result_keys=["epe_max_nm", "mrc_violations", "iterations_used"],
    sop_markdown="""# 掩模 OPC 优化 SOP

## 适用场景
图形在光刻后发生明显失真（线端回缩、拐角圆化），需要在掩模层面预补偿。

## 执行步骤
1. **载入版图**：校验图层与坐标系。
2. **OPC 迭代**：以 target_cd_nm 为目标，逐次修正边缘分段。
3. **MRC 检查**：确保修正后的图形可制造。
4. **导出掩模**：产出 opc_mask_file。

## 与其他仿真的关系
本仿真 **无前置任务**；其产物 `opc_mask_file` 可作为 `pattern_transfer` 的输入（可选）。

## 常见失败
- MRC 违例 > 0：降低 iterations 或放宽 target_cd_nm。
- 版图文件图层缺失：检查导出设置。
""",
)

PATTERN_TRANSFER = WorkflowSpec(
    id="pattern_transfer",
    name="光刻成像与图形转移",
    summary="仿真曝光与显影过程，输出光刻胶三维轮廓与 CD 均匀性报告。",
    when_to_use=("用户要看曝光剂量/焦距影响、工艺窗口、光刻胶轮廓，"
                 "或下游结构仿真（如 MEMS 模态）需要真实光刻轮廓时选这个。"),
    typical_minutes=15,
    cost_level="high",
    prerequisites=[
        PrerequisiteSpec(
            key="opc_mask_file", kind="artifact", required=False, auto_resolvable=True,
            produced_by_server="litho", produced_by_workflow="mask_opc",
            produced_artifact="opc_mask_file",
            description="OPC 修正后的掩模（可选；不提供则直接用原始 mask_file）",
            ask_hint="要不要先跑一道 OPC 优化？不跑就直接用原始掩模。"),
    ],
    steps=[
        StepSpec(id="import_mask", name="载入掩模", mock_seconds=1.0,
                 description="优先使用 upstream_artifacts.opc_mask_file，否则用 mask_file"),
        StepSpec(id="optical_imaging", name="光学成像计算", mock_seconds=4.0,
                 engine_hint="slitho_aerial.py", description="计算空中像强度分布"),
        StepSpec(id="resist_develop", name="光刻胶显影仿真", mock_seconds=4.0,
                 produces=["resist_profile_file"], engine_hint="slitho_resist.py",
                 description="产出三维光刻胶轮廓（下游结构仿真的输入）"),
        StepSpec(id="cd_measure", name="CD 与工艺窗口统计", mock_seconds=2.0,
                 produces=["cd_report"], description="统计 CD、均匀性、焦深、曝光宽容度"),
    ],
    params=[
        ParamSpec(name="mask_file", title="掩模文件", type="file", required=True,
                  example="/data/mask/resonator_v3.gds",
                  aliases=["版图", "gds", "掩模"],
                  ask_hint="请提供掩模/版图文件路径（如已做过 OPC，也可直接给 OPC 后的文件）",
                  description="原始掩模文件；若提供了 upstream_artifacts.opc_mask_file 则以后者为准"),
        ParamSpec(name="dose_mj_cm2", title="曝光剂量", type="number", unit="mJ/cm^2",
                  required=True, minimum=5.0, maximum=100.0, example=32.0,
                  aliases=["剂量", "dose", "曝光量"],
                  ask_hint="曝光剂量多少 mJ/cm^2？（常规 25~40，不确定可用 30）",
                  description="曝光剂量，直接影响 CD 与侧墙角"),
        ParamSpec(name="focus_nm", title="焦面偏移", type="number", unit="nm",
                  default=0.0, minimum=-200.0, maximum=200.0,
                  aliases=["焦距", "defocus", "焦面"],
                  description="相对最佳焦面的偏移，0 表示最佳焦面"),
        ParamSpec(name="resist_model", title="光刻胶模型", type="enum",
                  options=["CAR_standard", "CAR_advanced", "EUV_MET"],
                  default="CAR_standard", group="advanced",
                  description="光刻胶响应模型，EUV 工艺选 EUV_MET"),
        ParamSpec(name="resist_thickness_nm", title="光刻胶厚度", type="number", unit="nm",
                  default=90.0, minimum=10.0, maximum=1000.0, group="advanced",
                  description="光刻胶涂层厚度"),
    ] + _COMMON_OPTICS,
    artifacts=[
        ArtifactSpec(name="resist_profile_file",
                     description="光刻胶三维轮廓文件（.prf），是 Coventor 结构仿真的必需输入"),
        ArtifactSpec(name="cd_report", description="CD、均匀性与工艺窗口报告"),
    ],
    presets=[
        PresetSpec(id="nominal_193i", name="193i 标准工况",
                   description="ArF 浸没式标准工况（剂量 32、最佳焦面），只需再给掩模文件",
                   params={"dose_mj_cm2": 32.0, "focus_nm": 0.0, "na": 1.35,
                           "illumination": "annular", "resist_model": "CAR_standard"}),
        PresetSpec(id="process_window_scan", name="工艺窗口边界工况",
                   description="大焦偏下的边界条件，用于评估工艺余量",
                   params={"dose_mj_cm2": 36.0, "focus_nm": 60.0}),
    ],
    result_keys=["cd_nm", "cd_uniformity_3sigma_nm", "depth_of_focus_nm",
                 "exposure_latitude_pct", "sidewall_angle_deg"],
    sop_markdown="""# 光刻成像与图形转移 SOP

## 前置条件
- **必需**：掩模文件 `mask_file`。
- **可选**：`upstream_artifacts.opc_mask_file`（来自 litho.mask_opc）。对精度要求高时强烈建议先跑 OPC。

## 执行步骤
1. 载入掩模（有 OPC 产物优先用它）。
2. 光学成像计算（NA / 照明方式 / 波长）。
3. 光刻胶显影，产出 `resist_profile_file`。
4. CD 与工艺窗口统计，产出 `cd_report`。

## 下游使用
`resist_profile_file` 是 **coventor.mems_resonator_modal 的必需前置产物**。

## 参数经验
- 剂量每 +1 mJ/cm^2，CD 约变化 +1.5%。
- |focus| > 100nm 后 CD 均匀性迅速恶化，工艺窗口变窄。
""",
)

PLUGIN = SimPlugin(
    id="litho",
    name="S-Litho 光刻仿真",
    vendor="Synopsys S-Litho (mock)",
    description="光刻工艺仿真：掩模 OPC 优化、光学成像与光刻胶显影、CD 与工艺窗口分析。",
    instructions=(
        "本插件提供光刻相关仿真。使用铁律：\n"
        "1) 不确定跑哪个 → list_workflows；\n"
        "2) 确定后 → get_workflow 看步骤与前置依赖；\n"
        "3) 提交前必须 validate_params；\n"
        "4) 用户说“标准/常规参数”时优先用 preset_id；\n"
        "5) 光刻参数单位极容易错，剂量是 mJ/cm^2、长度是 nm，绝不要自行换算。"
    ),
    workflows=[MASK_OPC, PATTERN_TRANSFER],
)
```

---

## 7. `plugins/coventor_plugin.py` —— Coventor 插件（含**跳插件前置依赖**）

<aside>
🔗

看 `mems_resonator_modal` 的 `prerequisites`：它声明了自己需要 **litho 插件** `pattern_transfer` 产出的 `resist_profile_file`。这就是「让大模型知道前置任务」的具体写法 —— 它是**结构化声明**，不是提示词里的一句软约束。

</aside>

```python
"""Coventor MEMS 多物理场仿真插件声明。"""
from sim_mcp_common.contracts import (
    ArtifactSpec, ParamSpec, PresetSpec, PrerequisiteSpec, SimPlugin, StepSpec, WorkflowSpec,
)

_MOCK_PARAMS = [
    ParamSpec(name="mock_speed", title="mock 加速倍数", type="number", default=1.0,
              group="expert", internal=True, description="仅调试"),
    ParamSpec(name="mock_fail_step", title="mock 故障注入步骤", type="string",
              group="expert", internal=True, description="仅调试：制造求解失败"),
]

MODAL = WorkflowSpec(
    id="mems_resonator_modal",
    name="MEMS 谐振器模态分析",
    summary="对梁式谐振结构做模态求解，输出各阶固有频率、振型、等效刚度与品质因子。",
    when_to_use=("用户问谐振频率、固有频率、振型、模态、刚度、Q 值时选这个。"
                 "注意：必须先有光刻胶轮廓文件作为几何输入。"),
    typical_minutes=25,
    cost_level="high",
    prerequisites=[
        PrerequisiteSpec(
            key="resist_profile_file", kind="artifact", required=True, auto_resolvable=True,
            produced_by_server="litho", produced_by_workflow="pattern_transfer",
            produced_artifact="resist_profile_file",
            description="光刻胶三维轮廓文件，用作真实几何输入",
            ask_hint=("这个仿真需要光刻胶轮廓文件。我可以先自动跑一道光刻图形转移仿真（litho."
                       "pattern_transfer）来生成，也可以你直接提供已有的 .prf 文件路径。选哪种？")),
    ],
    steps=[
        StepSpec(id="import_geometry", name="导入几何与工艺栈", mock_seconds=2.0,
                 engine_hint="coventor_import.py",
                 description="由光刻胶轮廓 + 尺寸参数重建三维实体"),
        StepSpec(id="mesh_generation", name="网格划分", mock_seconds=3.0,
                 engine_hint="coventor_mesh.py", description="自适应或手动网格划分"),
        StepSpec(id="modal_solve", name="模态求解", mock_seconds=5.0,
                 produces=["modes_csv"], engine_hint="coventor_modal.py",
                 description="特征值求解，得到各阶固有频率"),
        StepSpec(id="post_process", name="后处理与报告", mock_seconds=2.0,
                 produces=["modal_report"], description="汇总频率/刚度/Q 值并生成报告"),
    ],
    params=[
        ParamSpec(name="beam_length_um", title="梁长", type="number", unit="um",
                  required=True, minimum=1.0, maximum=5000.0, example=200.0,
                  aliases=["长度", "梁长度", "L"],
                  ask_hint="谐振梁的长度是多少 µm？",
                  description="谐振梁长度，对频率影响最大（f ≈ 1/L^2）"),
        ParamSpec(name="beam_width_um", title="梁宽", type="number", unit="um",
                  required=True, minimum=0.5, maximum=1000.0, example=20.0,
                  aliases=["宽度", "W"],
                  ask_hint="谐振梁的宽度是多少 µm？",
                  description="谐振梁宽度"),
        ParamSpec(name="thickness_um", title="结构层厚度", type="number", unit="um",
                  required=True, minimum=0.1, maximum=100.0, example=2.0,
                  aliases=["厚度", "T"],
                  ask_hint="结构层厚度是多少 µm？",
                  description="结构层（可动层）厚度"),
        ParamSpec(name="material", title="结构材料", type="enum", required=True,
                  options=["Si", "SiO2", "SiN", "Al", "Poly"], example="Si",
                  aliases=["材料"],
                  ask_hint="结构材料选哪种？Si / SiO2 / SiN / Al / Poly",
                  description="弹性模量与密度由材料决定"),
        ParamSpec(name="num_modes", title="求解模态阶数", type="integer", default=5,
                  minimum=1, maximum=6, group="advanced",
                  description="需要输出前几阶模态，常规 5 阶"),
        ParamSpec(name="mesh_mode", title="网格模式", type="enum",
                  options=["auto", "manual"], default="auto", group="advanced",
                  description="auto 使用自适应网格；manual 需指定 mesh_size_um"),
        ParamSpec(name="mesh_size_um", title="网格尺寸", type="number", unit="um",
                  minimum=0.05, maximum=50.0, group="advanced",
                  required_if="mesh_mode == 'manual'",
                  ask_hint="你选了手动网格，请指定网格尺寸（µm）",
                  description="条件必填：仅当 mesh_mode=manual 时需要"),
        ParamSpec(name="damping_ratio", title="阻尼比", type="number", default=0.001,
                  minimum=0.0, maximum=1.0, group="advanced",
                  description="结构阻尼比，影响 Q 值估算"),
        ParamSpec(name="temperature_c", title="环境温度", type="number", unit="℃",
                  default=25.0, minimum=-273.0, maximum=1000.0, group="advanced",
                  description="环境温度，影响材料属性与热应力"),
    ] + _MOCK_PARAMS,
    artifacts=[
        ArtifactSpec(name="modes_csv", description="各阶模态频率 CSV", media_type="text/csv"),
        ArtifactSpec(name="modal_report", description="模态分析报告（含刚度、等效质量、Q 值）"),
    ],
    presets=[
        PresetSpec(id="standard_si_cantilever", name="标准硅悬臂梁",
                   description="200x20x2 µm 硅悬臂梁，5 阶模态（常用基准算例）",
                   params={"beam_length_um": 200.0, "beam_width_um": 20.0,
                           "thickness_um": 2.0, "material": "Si", "num_modes": 5}),
    ],
    result_keys=["first_mode_hz", "first_mode_khz", "modes_hz",
                 "spring_constant_n_per_m", "quality_factor"],
    sop_markdown="""# MEMS 谐振器模态分析 SOP

## 前置条件（重要）
**必需** `upstream_artifacts.resist_profile_file`，来自 `litho.pattern_transfer`。
原因：真实工艺下的梁截面不是理想矩形（有侧墙角、圆角），直接用理想几何会使频率偏差达 5~15%。

## 执行步骤
1. **导入几何与工艺栈**：用光刻胶轮廓 + 尺寸参数重建三维实体。
2. **网格划分**：auto 或 manual（manual 需 mesh_size_um）。
3. **模态求解**：输出前 num_modes 阶固有频率。
4. **后处理**：生成 modal_report，含 spring_constant_n_per_m（下游吸合分析会用）。

## 推荐的完整链路
litho.mask_opc（可选）→ litho.pattern_transfer（必需）→ coventor.mems_resonator_modal
→ coventor.mems_pull_in（可选）

## 常见失败与处理
- `SOLVER_DIVERGED`：网格过粗或长宽比极端 → 改 mesh_mode=manual 并减小 mesh_size_um。
- 频率明显偏高：检查 thickness_um 单位是否误用 nm。
""",
)

PULL_IN = WorkflowSpec(
    id="mems_pull_in",
    name="静电吸合电压分析",
    summary="扫描驱动电压，求出吸合电压、吸合间隙与可用行程。",
    when_to_use="用户问吸合电压、pull-in、驱动电压、可用行程、静电驱动稳定性时选这个。",
    typical_minutes=12,
    cost_level="medium",
    prerequisites=[
        PrerequisiteSpec(
            key="spring_constant_n_per_m", kind="job", required=False, auto_resolvable=True,
            produced_by_server="coventor", produced_by_workflow="mems_resonator_modal",
            produced_artifact="spring_constant_n_per_m",
            description="结构刚度（可选：不提供则用参数估算，提供则结果更准）",
            ask_hint="要不要先跑模态分析拿准确的刚度？不跑我就用估算值。"),
    ],
    steps=[
        StepSpec(id="build_model", name="建立静电-结构耦合模型", mock_seconds=2.0,
                 engine_hint="coventor_electro.py", description="建立平行板电容与弹性恢复力模型"),
        StepSpec(id="voltage_sweep", name="电压扫描", mock_seconds=4.0,
                 description="逐步升压，求解平衡位置"),
        StepSpec(id="detect_pullin", name="吸合点识别与报告", mock_seconds=2.0,
                 produces=["pullin_report"], description="识别失稳点并输出报告"),
    ],
    params=[
        ParamSpec(name="gap_um", title="初始气隙", type="number", unit="um", required=True,
                  minimum=0.1, maximum=50.0, example=2.0, aliases=["气隙", "间隙", "g0"],
                  ask_hint="可动结构与驱动电极的初始气隙是多少 µm？",
                  description="未加电时的电极间距z"),
        ParamSpec(name="voltage_sweep_max_v", title="扫描最大电压", type="number", unit="V",
                  required=True, minimum=1.0, maximum=500.0, example=60.0,
                  aliases=["最大电压", "扫描电压"],
                  ask_hint="电压扫到多少 V？（建议预估吸合电压的 1.5 倍）",
                  description="电压扫描上限"),
        ParamSpec(name="electrode_area_um2", title="电极正对面积", type="number", unit="um^2",
                  default=10000.0, minimum=1.0, group="advanced",
                  description="默认 100x100 µm 电极"),
        ParamSpec(name="spring_constant_n_per_m", title="结构刚度", type="number", unit="N/m",
                  group="advanced",
                  description="若提供了前置模态仿真结果，优先用前置值；否则可手填或用默认估算"),
    ] + _MOCK_PARAMS,
    artifacts=[
        ArtifactSpec(name="pullin_report", description="吸合电压与行程报告"),
    ],
    presets=[
        PresetSpec(id="typical_2um_gap", name="典型 2µm 气隙",
                   description="2 µm 气隙、100x100 µm 电极、扫到 60V",
                   params={"gap_um": 2.0, "voltage_sweep_max_v": 60.0,
                           "electrode_area_um2": 10000.0}),
    ],
    result_keys=["pull_in_voltage_v", "pull_in_gap_um", "travel_range_um"],
    sop_markdown="""# 静电吸合电压分析 SOP

## 前置条件
可选：`spring_constant_n_per_m`（来自 coventor.mems_resonator_modal）。
提供后吸合电压结果更可信；不提供则用默认估算值（仅供趋势判断）。

## 经验公式
V_pi = sqrt(8 k g0^3 / (27 eps0 A))，吸合发生在位移达到 g0/3 处。
所以可用行程约为 g0/3，这是 MEMS 设计的硬约束。
""",
)

PLUGIN = SimPlugin(
    id="coventor",
    name="Coventor MEMS 多物理场仿真",
    vendor="Coventor MEMS+ (mock)",
    description="MEMS 结构仿真：模态分析、静电吸合分析等多物理场耦合计算。",
    instructions=(
        "本插件提供 MEMS 结构仿真。特别注意：\n"
        "1) mems_resonator_modal **必须**先有 litho.pattern_transfer 产出的 resist_profile_file；\n"
        "2) 涉及两个以上仿真时，请用 submit_plan 编排 DAG，不要手动一步步跑；\n"
        "3) 长度单位统一为 µm，电压为 V；用户说“2 微米”就是 2.0，不要换算成 nm；\n"
        "4) 参数不全时用 validate_params 的 ask_user_schema 去问用户，绝不自己编造尺寸。"
    ),
    workflows=[MODAL, PULL_IN],
)
```

---

## 8. 入口脚本（每个 6 行）

`servers/litho_server.py`：

```python
import os

from plugins.litho_plugin import PLUGIN
from sim_mcp_common.server_factory import build_server

mcp, store = build_server(
    PLUGIN,
    host=os.getenv("HOST", "0.0.0.0"),
    port=int(os.getenv("PORT", "9102")),
    workspace=os.getenv("WORKSPACE", "./_workspace/litho"),
)

if __name__ == "__main__":
    mcp.run(transport="streamable-http")   # 监听 http://0.0.0.0:9102/mcp
```

`servers/coventor_server.py`：

```python
import os

from plugins.coventor_plugin import PLUGIN
from sim_mcp_common.server_factory import build_server

mcp, store = build_server(
    PLUGIN,
    host=os.getenv("HOST", "0.0.0.0"),
    port=int(os.getenv("PORT", "9101")),
    workspace=os.getenv("WORKSPACE", "./_workspace/coventor"),
)

if __name__ == "__main__":
    mcp.run(transport="streamable-http")   # 监听 http://0.0.0.0:9101/mcp
```

还需要两个空文件（很多人忘了这一步导致 import 失败）：

```bash
touch sim_mcp_common/__init__.py plugins/__init__.py servers/__init__.py
```

---

## 9. `app_wrapper.py` —— 生产部署（Bearer 鉴权 + uvicorn）

`mcp.run()` 适合本地调试；上生产需要**鉴权**，用 Starlette 包一层即可：

```python
"""生产入口：uvicorn app_wrapper:app --host 0.0.0.0 --port 9101

环境变量：
  PLUGIN=coventor|litho    选择加载哪个插件
  MCP_TOKEN=xxxx           设了就开启 Bearer 鉴权（Java 侧在 headers 里配同样的 token）
"""
import contextlib
import os

from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse
from starlette.routing import Mount

from sim_mcp_common.server_factory import build_server

_PLUGIN_NAME = os.getenv("PLUGIN", "coventor")
if _PLUGIN_NAME == "litho":
    from plugins.litho_plugin import PLUGIN
else:
    from plugins.coventor_plugin import PLUGIN

mcp, store = build_server(
    PLUGIN,
    workspace=os.getenv("WORKSPACE", "./_workspace/%s" % _PLUGIN_NAME),
)

class BearerAuthMiddleware(BaseHTTPMiddleware):
    """最简鉴权：健康检查放行，其余请求验 Bearer token。"""

    async def dispatch(self, request, call_next):
        if request.url.path.rstrip("/") in ("/healthz",):
            return await call_next(request)
        token = os.getenv("MCP_TOKEN")
        if token:
            if request.headers.get("authorization", "") != "Bearer %s" % token:
                return JSONResponse({"error": "unauthorized"}, status_code=401)
        return await call_next(request)

@contextlib.asynccontextmanager
async def lifespan(_app):
    # 必须启动 session manager，否则 Streamable HTTP 会报错
    async with contextlib.AsyncExitStack() as stack:
        await stack.enter_async_context(mcp.session_manager.run())
        yield

app = Starlette(
    routes=[Mount("/", app=mcp.streamable_http_app())],
    middleware=[Middleware(BearerAuthMiddleware)],
    lifespan=lifespan,
)
```

<aside>
🧪

**不启 Java 也能先自测**：`python -c "from plugins.coventor_plugin import PLUGIN; from sim_mcp_common.validation import validate; print(validate(PLUGIN, PLUGIN.workflow('mems_resonator_modal'), {'beam_length_um':200}).model_dump_json(indent=2))"` —— 你会直接看到 `next_action=RUN_PREREQUISITE`、缺失参数清单、以及自动生成的表单 Schema。

</aside>