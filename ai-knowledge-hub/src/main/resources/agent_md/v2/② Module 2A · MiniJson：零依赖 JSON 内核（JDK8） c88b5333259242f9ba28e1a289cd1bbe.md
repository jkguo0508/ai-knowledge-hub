# ② Module 2A · MiniJson：零依赖 JSON 内核（JDK8）

<aside>
🎯

因为约束是「不引入第三方库」，而 JSON-RPC 与 LLM 报文都需要双向序列化，所以先把地基打好：**一个 300 行以内、无依赖、JDK8 可编译的 JSON 内核**。它额外提供三个工程上很关键的能力：`parseLoose`（容忍 LLM 输出的 Markdown 围栏）、`get(root, "a.b.0.c")`（路径取值）、`deepCopy`（模版→实例快照）。

</aside>

## 类型映射约定

| JSON | Java |
| --- | --- |
| object | `LinkedHashMap<String,Object>`（**保序**，便于日志比对） |
| array | `ArrayList<Object>` |
| string | `String` |
| 整数 | `Long` |
| 小数 / 科学计数 | `Double` |
| true / false | `Boolean` |
| null | `null` |

## `src/main/java/com/sim/agent/json/MiniJson.java`

```java
package com.sim.agent.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖 JSON 解析 / 序列化内核（JDK8 可编译，无任何第三方 jar）。
 * 线程安全：实例非线程安全，但所有对外的 static 方法都是无状态的，可并发调用。
 */
public final class MiniJson {

    /** 解析/序列化异常 */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JsonException(String msg) { super(msg); }
    }

    private final String src;
    private int pos;

    private MiniJson(String src) { this.src = src; }

    /* ==================================================================
     *  一、解析
     * ================================================================== */

    public static Object parse(String text) {
        if (text == null) throw new JsonException("JSON 文本为 null");
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.src.length()) {
            throw new JsonException("JSON 尾部存在多余字符, offset=" + p.pos + ", 片段="
                    + p.src.substring(p.pos, Math.min(p.src.length(), p.pos + 32)));
        }
        return v;
    }

    /**
     * 宽松解析：专门对付 LLM 的不听话输出。
     * 能处理：```json 包裹、前后缀客套话、仅截取第一个完整 JSON 对象/数组。
     */
    public static Object parseLoose(String text) {
        if (text == null) return null;
        String s = text.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        int ob = s.indexOf('{');
        int oa = s.indexOf('[');
        int begin = (ob < 0) ? oa : (oa < 0 ? ob : Math.min(ob, oa));
        if (begin < 0) return null;
        char close = (s.charAt(begin) == '{') ? '}' : ']';
        int last = s.lastIndexOf(close);
        if (last <= begin) return null;
        return parse(s.substring(begin, last + 1));
    }

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') pos++;
            else break;
        }
    }

    private Object value() {
        if (pos >= src.length()) throw new JsonException("JSON 意外结束");
        char c = src.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '\"': return string();
            case 't': literal("true");  return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null");  return null;
            default:  return number();
        }
    }

    private void literal(String lit) {
        if (!src.startsWith(lit, pos)) throw new JsonException("非法字面量, offset=" + pos);
        pos += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++;                                  // 跃过 '{'
        ws();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (pos >= src.length() || src.charAt(pos) != ':')
                throw new JsonException("对象缺少 ':' offset=" + pos);
            pos++;
            ws();
            m.put(k, value());
            ws();
            if (pos >= src.length()) throw new JsonException("对象未闭合");
            char c = src.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == '}') { pos++; return m; }
            throw new JsonException("对象出现非法字符 '" + c + "' offset=" + pos);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        pos++;                                  // 跃过 '['
        ws();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (pos >= src.length()) throw new JsonException("数组未闭合");
            char c = src.charAt(pos);
            if (c == ',') { pos++; continue; }
            if (c == ']') { pos++; return l; }
            throw new JsonException("数组出现非法字符 '" + c + "' offset=" + pos);
        }
    }

    private String string() {
        if (pos >= src.length() || src.charAt(pos) != '\"')
            throw new JsonException("期望字符串起始引号, offset=" + pos);
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new JsonException("字符串未闭合");
            char c = src.charAt(pos++);
            if (c == '\"') return sb.toString();
            if (c != '\\\\') { sb.append(c); continue; }
            if (pos >= src.length()) throw new JsonException("转义字符不完整");
            char e = src.charAt(pos++);
            switch (e) {
                case '\"': sb.append('\"'); break;
                case '\\\\': sb.append('\\\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\\b'); break;
                case 'f':  sb.append('\\f'); break;
                case 'n':  sb.append('\\n'); break;
                case 'r':  sb.append('\\r'); break;
                case 't':  sb.append('\\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) throw new JsonException("\\\\u 转义不完整");
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default: throw new JsonException("非法转义 \\\\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        boolean fractional = false;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') { pos++; }
            else if (c == '.' || c == 'e' || c == 'E') { fractional = true; pos++; }
            else if (c == '+' || c == '-') { pos++; }        // 指数符号
            else break;
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty() || "-".equals(raw)) throw new JsonException("非法数字, offset=" + start);
        try {
            if (!fractional) return Long.valueOf(Long.parseLong(raw));
            return Double.valueOf(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            try { return Double.valueOf(Double.parseDouble(raw)); }
            catch (NumberFormatException e2) { throw new JsonException("无法解析数字: " + raw); }
        }
    }

    /* ==================================================================
     *  二、序列化
     * ================================================================== */

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder(256);
        write(o, sb);
        return sb.toString();
    }

    /** 缩进版，仅用于日志与调试输出 */
    public static String pretty(Object o) {
        StringBuilder sb = new StringBuilder(256);
        writePretty(o, sb, 0);
        return sb.toString();
    }

    private static void write(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String)  { quote((String) o, sb); return; }
        if (o instanceof Boolean) { sb.append(((Boolean) o).booleanValue() ? "true" : "false"); return; }
        if (o instanceof Number)  { sb.append(numberToString((Number) o)); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(e, sb);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Object[]) { write(Arrays.asList((Object[]) o), sb); return; }
        if (o instanceof Enum) { quote(((Enum<?>) o).name(), sb); return; }
        quote(String.valueOf(o), sb);            // 兵底：toString
    }

    private static void writePretty(Object o, StringBuilder sb, int depth) {
        String pad = indent(depth + 1);
        if (o instanceof Map && !((Map<?, ?>) o).isEmpty()) {
            sb.append("{\\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(",\\n");
                first = false;
                sb.append(pad);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                writePretty(e.getValue(), sb, depth + 1);
            }
            sb.append('\\n').append(indent(depth)).append('}');
            return;
        }
        if (o instanceof Collection && !((Collection<?>) o).isEmpty()) {
            sb.append("[\\n");
            boolean first = true;
            for (Object e : (Collection<?>) o) {
                if (!first) sb.append(",\\n");
                first = false;
                sb.append(pad);
                writePretty(e, sb, depth + 1);
            }
            sb.append('\\n').append(indent(depth)).append(']');
            return;
        }
        write(o, sb);
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('\"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\\\""); break;
                case '\\\\': sb.append("\\\\\\\\"); break;
                case '\\n': sb.append("\\\\n"); break;
                case '\\r': sb.append("\\\\r"); break;
                case '\\t': sb.append("\\\\t"); break;
                case '\\b': sb.append("\\\\b"); break;
                case '\\f': sb.append("\\\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\\\u%04x", Integer.valueOf(c)));
                    else sb.append(c);
            }
        }
        sb.append('\"');
    }

    private static String numberToString(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
            if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return n.toString();
    }

    /* ==================================================================
     *  三、工程工具集（这部分才是真正降低上层代码量的关键）
     * ================================================================== */

    /** 便捷构造有序 Map：map("a",1,"b","x") —— JDK8 没有 Map.of */
    public static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0) throw new JsonException("map(...) 参数必须成对");
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public static List<Object> list(Object... items) {
        return new ArrayList<Object>(Arrays.asList(items));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        if (o instanceof List) return (List<Object>) o;
        if (o instanceof Collection) return new ArrayList<Object>((Collection<Object>) o);
        List<Object> l = new ArrayList<Object>();
        if (o != null) l.add(o);
        return l;
    }

    /** 路径取值：get(root, "choices.0.message.content")，任何一段不匹配返回 null */
    public static Object get(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) return root;
        Object cur = root;
        for (String seg : path.split("\\\\.")) {
            if (cur == null) return null;
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(seg);
            } else if (cur instanceof List) {
                int idx;
                try { idx = Integer.parseInt(seg); } catch (NumberFormatException e) { return null; }
                List<?> l = (List<?>) cur;
                if (idx < 0 || idx >= l.size()) return null;
                cur = l.get(idx);
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String getString(Object root, String path, String def) {
        Object v = get(root, path);
        if (v == null) return def;
        if (v instanceof String) return (String) v;
        if (v instanceof Number) return numberToString((Number) v);
        if (v instanceof Boolean) return v.toString();
        return stringify(v);
    }

    public static long getLong(Object root, String path, long def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong(((String) v).trim()); } catch (Exception ignore) { }
        }
        return def;
    }

    public static double getDouble(Object root, String path, double def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble(((String) v).trim()); } catch (Exception ignore) { }
        }
        return def;
    }

    public static boolean getBoolean(Object root, String path, boolean def) {
        Object v = get(root, path);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) return "true".equalsIgnoreCase(((String) v).trim());
        return def;
    }

    public static Map<String, Object> getMap(Object root, String path) {
        return asMap(get(root, path));
    }

    public static List<Object> getList(Object root, String path) {
        Object v = get(root, path);
        return (v == null) ? new ArrayList<Object>() : asList(v);
    }

    /** 深拷贝：模版 -> 实例快照、参数隔离必须靠它，否则会出现多节点共享一个 Map 的災难现场 */
    public static Object deepCopy(Object o) {
        if (o instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                m.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            }
            return m;
        }
        if (o instanceof Collection) {
            List<Object> l = new ArrayList<Object>();
            for (Object e : (Collection<?>) o) l.add(deepCopy(e));
            return l;
        }
        return o;                                 // String/Number/Boolean/null 均不可变
    }

    public static Map<String, Object> deepCopyMap(Object o) {
        return asMap(deepCopy(o));
    }

    /* ==================================================================
     *  四、自检：java -cp out com.sim.agent.json.MiniJson
     * ================================================================== */
    public static void main(String[] args) {
        String raw = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"isError\":false,"
                + "\"structuredContent\":{\"job_id\":\"lit-abc\",\"progress\":0.375,"
                + "\"outputs\":{\"cd_nm\":44.71,\"tags\":[\"a\",\"b\"]},"
                + "\"msg\":\"\\u4eff\\u771f\\u5b8c\\u6210\\n\\u7b2c\\u4e8c\\u884c\"}}}";
        Object o = parse(raw);
        System.out.println("1. id            = " + getLong(o, "id", -1));
        System.out.println("2. job_id        = " + getString(o, "result.structuredContent.job_id", "?"));
        System.out.println("3. progress      = " + getDouble(o, "result.structuredContent.progress", -1));
        System.out.println("4. cd_nm         = " + getDouble(o, "result.structuredContent.outputs.cd_nm", -1));
        System.out.println("5. tags[1]       = " + getString(o, "result.structuredContent.outputs.tags.1", "?"));
        System.out.println("6. \\\\uXXXX 与换行   = " + getString(o, "result.structuredContent.msg", "?").replace("\\n", "\\\\n"));
        System.out.println("7. 回写再读一致  = " + stringify(parse(stringify(o))).equals(stringify(o)));
        System.out.println("8. parseLoose    = " + stringify(parseLoose("好的，给你：\\n```json\\n{\\"a\\": [1, 2.5, true, null]}\\n```\\n以上。")));
        Map<String, Object> src = map("p", map("deep", list(1, 2)));
        Map<String, Object> cp = deepCopyMap(src);
        asList(get(cp, "p.deep")).add(3);
        System.out.println("9. deepCopy 隔离  = 原=" + stringify(src) + " 副本=" + stringify(cp));
    }
}
```

## 自检预期输出

```
$ javac -encoding UTF-8 -d out src/main/java/com/sim/agent/json/MiniJson.java
$ java -cp out com.sim.agent.json.MiniJson
1. id            = 7
2. job_id        = lit-abc
3. progress      = 0.375
4. cd_nm         = 44.71
5. tags[1]       = b
6. \uXXXX 与换行   = 仿真完成\n第二行
7. 回写再读一致  = true
8. parseLoose    = {"a":[1,2.5,true,null]}
9. deepCopy 隔离  = 原={"p":{"deep":[1,2]}} 副本={"p":{"deep":[1,2,3]}}
```

<aside>
💡

**为什么整数解成 `Long`、小数解成 `Double`？** 因为 JSON-RPC 的 `id` 必须能原值回带对齐。如果统一成 `Double`，`id=1` 会变成 `1.0`，与服务端回传的 `1` 对不上，就会出现「请求永远超时」的鬼故事。`McpClient` 里统一用 `((Number) id).longValue()` 做 key 归一化，双重保险。

</aside>