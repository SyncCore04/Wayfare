#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Controller 的真实注解生成接口清单 —— 不手写、不抄设计文档。

用法（仓库根目录）：
    "C:/Users/Apollo/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" scripts/gen-api-list.py

产出：
  1. docs/接口清单.md        —— 全量清单（按 Controller 分组）
  2. README.md 里 <!-- BEGIN:API-LIST --> 与 <!-- END:API-LIST --> 之间的**汇总表**

数据来源（全部来自真实代码，不读设计文档）：
  · controller/*.java 的 @RequestMapping / @GetMapping / @PostMapping / @PutMapping / @DeleteMapping
  · security/JwtInterceptor.java 的 PUBLIC_PATHS 常量（「哪些接口允许匿名」的唯一权威）
  · 方法体里是否出现 checkAdmin() → 判定「管理员」
  · 方法上方最近一段 Javadoc 的首句 → 作为「说明」

⚠️ 这个脚本存在的意义：README 里的接口清单**必须**由它生成。
   手写清单一定会和代码漂移 —— 本项目已有过一次教训。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CTRL_DIR = os.path.join(ROOT, "wayfare-backend", "src", "main", "java", "com", "wayfare", "controller")
JWT_FILE = os.path.join(ROOT, "wayfare-backend", "src", "main", "java", "com", "wayfare", "security", "JwtInterceptor.java")
API_MD = os.path.join(ROOT, "docs", "接口清单.md")
README = os.path.join(ROOT, "README.md")

BEGIN = "<!-- BEGIN:API-LIST -->"
END = "<!-- END:API-LIST -->"

MAPPING = re.compile(r"^\s*@(Get|Post|Put|Delete)Mapping\b\s*(\(([^)]*)\))?")
CLASS_MAPPING = re.compile(r"@RequestMapping\s*\(\s*\"([^\"]*)\"")


def read(path):
    return io.open(path, encoding="utf-8").read()


def load_public_paths():
    """从 JwtInterceptor 的 PUBLIC_PATHS 常量里取白名单（唯一权威）。"""
    src = read(JWT_FILE)
    m = re.search(r"PUBLIC_PATHS\s*=\s*(?:List\.of|Set\.of|new\s+\w+\s*<>\s*\(\s*List\.of)\s*\((.*?)\)\s*;", src, re.S)
    if not m:
        m = re.search(r"PUBLIC_PATHS[^=]*=\s*(.*?);", src, re.S)
    body = m.group(1) if m else ""
    return set(re.findall(r'"([^"]+)"', body))


def is_public(full, public):
    """白名单匹配。要做两步归一，否则会少算：

    1. 白名单里写的是 `{id:[0-9]+}`（正则约束），注解里写的是 `{id}` —— 去掉 `:regex` 再比；
    2. 白名单里有 `**` 前缀匹配（如 `/comments/work/**`），要按前缀比。
    """
    for p in public:
        norm = re.sub(r"\{([A-Za-z_]\w*):[^}]*\}", r"{\1}", p)
        if norm == full:
            return True
        if norm.endswith("/**") and full.startswith(norm[:-3]):
            return True
    return False


def first_string(arg_text):
    m = re.search(r'"([^"]*)"', arg_text or "")
    return m.group(1) if m else ""


def javadoc_summary(lines, idx):
    """向上找最近的 /** ... */ 块，返回首句（去掉 @tag 行）。

    需要在「注解行」与「上一个方法的 `}`」之间做区分：所以向上扫时
    遇到明显是代码的行（含 `;` / 以 `{` 或 `}` 结尾）就立刻放弃 ——
    否则会把上一个方法的注释错认成当前方法的。
    """
    lo = max(0, idx - 20)
    end = None
    for k in range(idx - 1, lo - 1, -1):
        s = lines[k].strip()
        if s.endswith("*/"):
            end = k
            break
        if (";" in s) or s.endswith("{") or s.endswith("}"):
            return ""
    if end is None:
        return ""
    start = None
    for k in range(end, lo - 1, -1):
        if "/**" in lines[k]:
            start = k
            break
    if start is None:
        return ""
    out = []
    for k in range(start, end + 1):
        s = lines[k].strip()
        s = re.sub(r"^/\*\*+", "", s)
        s = re.sub(r"\*+/$", "", s)
        s = re.sub(r"^\*", "", s).strip()
        if not s or s.startswith("@"):
            continue
        out.append(s)
    return out[0] if out else ""


def scan_controller(path):
    lines = read(path).splitlines()
    cls = os.path.basename(path)[:-5]
    # 类级路径 + 类级 Javadoc 标题
    base = ""
    cls_title = ""
    for i, ln in enumerate(lines):
        m = CLASS_MAPPING.search(ln)
        if m and not base:
            base = m.group(1)
        if re.match(r"\s*public class\s+" + cls, ln):
            cls_title = javadoc_summary(lines, i)
            break
    rows = []
    for i, ln in enumerate(lines):
        m = MAPPING.match(ln)
        if not m:
            continue
        verb = m.group(1).upper()
        sub = first_string(m.group(3))
        # 方法体：从本行到下一个 mapping 注解（或文件末尾）
        j = i + 1
        body = []
        while j < len(lines) and not MAPPING.match(lines[j]):
            body.append(lines[j])
            j += 1
        body_text = "\n".join(body)
        rows.append({
            "verb": verb,
            "full": (base + sub) if sub else base,
            "desc": javadoc_summary(lines, i),
            "admin": "checkAdmin()" in body_text,
        })
    return {"cls": cls, "title": cls_title, "base": base, "rows": rows}


def main():
    public = load_public_paths()
    files = sorted(f for f in os.listdir(CTRL_DIR) if f.endswith(".java"))
    ctrls = [scan_controller(os.path.join(CTRL_DIR, f)) for f in files]

    total = sum(len(c["rows"]) for c in ctrls)
    pub_n = 0
    adm_n = 0

    md = []
    md.append("# Wayfare 接口清单")
    md.append("")
    md.append("> ⚠️ **本文件由 `scripts/gen-api-list.py` 自动生成，请勿手改。**")
    md.append("> 数据来源：`controller/*.java` 的真实映射注解 + `security/JwtInterceptor.java` 的 `PUBLIC_PATHS` 常量。")
    md.append("> 所有路径都带 context-path `/api`，例如 `GET /works/page` 的真实地址是 `http://localhost:8080/api/works/page`。")
    md.append("")
    md.append("权限列含义：")
    md.append("")
    md.append("- **公开** —— 该**路径**命中 `PUBLIC_PATHS` 白名单，未登录可访问。")
    md.append("  ⚠️ 白名单**按路径匹配、不区分 HTTP 方法**，所以 `PUT` / `DELETE /works/{id}` 也落在白名单里 ——")
    md.append("  它们**能不能真的操作**由方法内部的作者校验决定（这正是「先认身份、再判放行」的设计）。")
    md.append("- **管理员** —— 方法体里调用了 `checkAdmin()`。")
    md.append("- **登录** —— 其余，需要有效 JWT。")
    md.append("")
    md.append("> 局限（如实说明）：**运行期**才能判定的权限（作者本人 / 资源归属）无法从注解静态推导，")
    md.append("> 这类接口只会显示它的「路径级」权限。要确认某个接口的真实权限，请读方法体。")
    md.append("")
    md.append("共 **%d** 个端点，分布在 **%d** 个 Controller。" % (total, len(ctrls)))
    md.append("")

    summary = []
    summary.append(BEGIN)
    summary.append("")
    summary.append("| Controller | 端点数 | 公开 | 管理员 | 类级路径 |")
    summary.append("|---|---|---|---|---|")
    for c in ctrls:
        rows = c["rows"]
        p = sum(1 for r in rows if is_public(r["full"], public))
        a = sum(1 for r in rows if r["admin"])
        pub_n += p
        adm_n += a
        summary.append("| `%s` | %d | %d | %d | `%s` |" % (c["cls"], len(rows), p, a, c["base"]))
    summary.append("| **合计** | **%d** | **%d** | **%d** | — |" % (total, pub_n, adm_n))
    summary.append("")
    summary.append("> 完整清单（含每个端点的说明与权限）见 [`docs/接口清单.md`](docs/接口清单.md)，"
                   "由 `scripts/gen-api-list.py` 从真实注解生成。")
    summary.append("")
    summary.append(END)

    for c in ctrls:
        title = (c["title"] or c["cls"]).rstrip("。. ")
        md.append("## %s（`%s`）" % (title, c["cls"]))
        md.append("")
        md.append("类级路径：`%s`" % c["base"])
        md.append("")
        md.append("| 方法 | 路径 | 权限 | 说明 |")
        md.append("|---|---|---|---|")
        for r in c["rows"]:
            perm = "公开" if is_public(r["full"], public) else ("管理员" if r["admin"] else "登录")
            desc = (r["desc"] or "").replace("|", "/").strip()
            md.append("| %s | `%s` | %s | %s |" % (r["verb"], r["full"], perm, desc))
        md.append("")

    io.open(API_MD, "w", encoding="utf-8", newline="\n").write("\n".join(md))

    # 注入 README
    if os.path.exists(README):
        txt = read(README)
        if BEGIN in txt and END in txt:
            pre = txt[: txt.index(BEGIN)]
            post = txt[txt.index(END) + len(END):]
            txt = pre + "\n".join(summary) + post
        else:
            print("!! README 里没有 %s / %s 标记，未注入（请先加标记）" % (BEGIN, END), file=sys.stderr)
            txt = None
        if txt is not None:
            io.open(README, "w", encoding="utf-8", newline="\n").write(txt)

    print("已生成 %s：%d 个端点 / %d 个 Controller（公开 %d，管理员 %d）" % (
        os.path.relpath(API_MD, ROOT), total, len(ctrls), pub_n, adm_n))


if __name__ == "__main__":
    main()
