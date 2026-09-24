#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验仓库内 Markdown 文档的**跨文档链接**与**锚点**是否有效。

用法（仓库根目录）：
    "C:/Users/Apollo/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" scripts/check-md-links.py

背景（为什么要写它）：
文档多了之后，「链接指向一个已经改名的章节」这种错误**不会报错、也没有任何症状** ——
只有人点进去才发现是 404。本项目在 P8-A / P8-B 两次文档重构中各手工抓出 3 处断链，
说明靠肉眼审是靠不住的。

判定规则（按 GitHub 的锚点算法归一化，**中文标题也支持**）：
  1. 取标题行的文本
  2. 删除这些字符： 反引号 星号 方括号 圆括号 全角/半角括号 冒号 顿号 逗号 句号 引号
                    直角引号 斜杠 点 感叹号 问号 分号
  3. 英文转小写
  4. 空格 → 连字符

⚠️ 第 2 步里**必须包含全角括号（）与顿号、** —— 中文标题「四、连接器层（本系统地基）」
   归一化后是 `四连接器层本系统地基`；只删半角括号会得到带全角括号的锚点，从而**误报**。

退出码：0 = 全部有效；1 = 存在断链或坏锚点（可直接用于 CI / 提交前检查）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 要删除的标点（半角 + 全角都要，见文件头 ⚠️）
# 🔴 别漏掉半角句点 `.` 与半角括号 `()`：
#    标题「### 4.4 熔断器」的锚点是 `44-熔断器`（点被删掉）；漏了 `.` 就会算成 `4.4-熔断器` 而误报。
STRIP_CHARS = '`*[]()：、，。.""\'\'「」/\\!！?？;；（）.'

# 要检查的文档（相对仓库根）；新增文档时在这里登记
DOCS = [
    'README.md',
    'docs/交接文档.md',
    'docs/Wayfare-开发文档.md',
    'docs/部署说明.md',
    'docs/交付说明.md',
    'docs/接口清单.md',
    'docs/测试用例表.md',
    'docs/已知缺陷与修复.md',
    'docs/metrics.md',
    'docs/drill-report.md',
]

LINK_RE = re.compile(r'\]\((?!https?://|mailto:)([^)\s]+)\)')
HEADING_RE = re.compile(r'^#{1,6}\s+(.+?)\s*$', re.M)


def anchors_of(text):
    """把文档里所有标题归一化成锚点集合"""
    out = set()
    for m in HEADING_RE.finditer(text):
        t = m.group(1)
        for ch in STRIP_CHARS:
            t = t.replace(ch, '')
        out.add(t.replace(' ', '-').lower())
    return out


def main():
    present = [d for d in DOCS if os.path.exists(os.path.join(ROOT, d))]
    missing = [d for d in DOCS if d not in present]
    for d in missing:
        print('[skip] 文件不存在（不算错误）: %s' % d)

    texts = {}
    for d in present:
        try:
            texts[os.path.basename(d)] = io.open(os.path.join(ROOT, d), encoding='utf-8').read()
        except Exception as e:
            print('[warn] 读取失败 %s: %s' % (d, e))
    anchor_map = {name: anchors_of(t) for name, t in texts.items()}

    problems = []
    checked = 0
    for d in present:
        src = os.path.join(ROOT, d)
        src_dir = os.path.dirname(src)
        text = texts[os.path.basename(d)]
        for m in LINK_RE.finditer(text):
            link = m.group(1)
            if link.startswith('#'):
                raw_path, anchor = '', link[1:]          # 文档内跳转
            elif '#' in link:
                raw_path, anchor = link.split('#', 1)
            else:
                raw_path, anchor = link, ''
            # anchor_map 的键是 basename；存在性判断要用**原始相对路径**（别用 basename，
            # 否则 `scripts/gen-api-list.py` 会被当成「根目录下的 gen-api-list.py」而误报不存在）
            base = os.path.basename(raw_path) if raw_path else os.path.basename(d)
            checked += 1

            if base not in anchor_map:
                # 目标不是「已登记待校验的 md」，按相对本文件的路径存在性检查
                cands = [os.path.join(src_dir, raw_path),
                         os.path.join(ROOT, raw_path),
                         os.path.join(ROOT, 'docs', raw_path)]
                if not any(os.path.exists(c) for c in cands):
                    problems.append('%s -> %s  （文件不存在）' % (d, link))
                continue

            if anchor and anchor not in anchor_map[base]:
                problems.append('%s -> %s  （锚点不存在）' % (d, link))

    print('')
    print('已校验 %d 条站内链接，覆盖 %d 个文档' % (checked, len(present)))
    if problems:
        print('')
        print('发现 %d 个问题：' % len(problems))
        for p in problems:
            print('  ✗ ' + p)
        return 1
    print('✅ 全部有效')
    return 0


if __name__ == '__main__':
    sys.exit(main())
