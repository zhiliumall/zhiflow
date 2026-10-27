#!/usr/bin/env python3
"""按 ArchUnit 违例清单给写语义 Controller 方法补 @SysLog（S3）。

日志文案取自方法自身的 @Operation(summary = "...")——那是开发者已经写好的中文描述，
比从方法名反推准确。没有 summary 的方法会被跳过并列出，交人工决定文案，绝不用
「保存」「更新」这类无信息量的兜底文案糊过去：审计日志的 title 是给人看的，
糊弄的文案等于没有。

用法：
    python3 add-syslog.py <violations-syslog.txt> [模块路径前缀 ...]

只处理指定模块（不传则全部）。每次跑完必须重新编译 + 跑门禁验证违例数下降。
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

SYSLOG_IMPORT = "import com.zhiflow.common.log.annotation.SysLog;"

# 类全名前缀 -> 源码根
MODULE_ROOTS = [
    ("com.zhiflow.upms.", "zhiflow-upms/zhiflow-upms-admin/src/main/java"),
    ("com.zhiflow.mall.admin.", "zhiflow-mall/zhiflow-mall-admin/src/main/java"),
    ("com.zhiflow.mall.api.", "zhiflow-mall/zhiflow-mall-api/src/main/java"),
    ("com.zhiflow.im.", "zhiflow-im/zhiflow-im-server/src/main/java"),
    ("com.zhiflow.live.", "zhiflow-live/zhiflow-live-admin/src/main/java"),
    ("com.zhiflow.pay.", "zhiflow-pay/zhiflow-pay-admin/src/main/java"),
    ("com.zhiflow.weixin.", "zhiflow-weixin/zhiflow-weixin-admin/src/main/java"),
    ("com.zhiflow.auth.", "zhiflow-auth/src/main/java"),
    ("com.zhiflow.codegen.", "zhiflow-codegen/src/main/java"),
]

VIOLATION_RE = re.compile(r"^(?P<fqcn>[\w.$]+)\.(?P<method>\w+)\((?P<params>.*)\) 缺少 @SysLog$")

# 方法声明行：public/protected 返回类型 方法名(
DECL_RE_TMPL = r"^(?P<indent>[ \t]*)(public|protected)\s+[\w<>,\[\]\.\? ]+\s+{method}\s*\("

MAPPING_RE = re.compile(r"^\s*@(Post|Put|Delete|Patch|Request)Mapping\b")
OPERATION_RE = re.compile(r'@Operation\s*\(\s*summary\s*=\s*"([^"]+)"')


def source_path(fqcn: str) -> Path | None:
    for prefix, root in MODULE_ROOTS:
        if fqcn.startswith(prefix):
            outer = fqcn.split("$")[0]
            return Path(root) / (outer.replace(".", "/") + ".java")
    return None


def find_method_decl(lines: list[str], method: str, param_count: int) -> int | None:
    """返回方法声明行索引。同名重载用参数个数区分（按左括号后逗号数粗算）。"""
    decl_re = re.compile(DECL_RE_TMPL.format(method=re.escape(method)))
    candidates = []
    for i, line in enumerate(lines):
        if not decl_re.match(line):
            continue
        # 合并到右括号，数参数个数
        sig = line
        j = i
        while sig.count("(") > sig.count(")") and j + 1 < len(lines):
            j += 1
            sig += lines[j]
        inner = sig[sig.index("(") + 1: sig.rindex(")")] if ")" in sig else ""
        count = 0 if not inner.strip() else inner.count(",") + 1
        candidates.append((i, count))
    if not candidates:
        return None
    exact = [i for i, c in candidates if c == param_count]
    if len(exact) == 1:
        return exact[0]
    # 参数个数也无法区分时放弃，交人工——错插比不插危险
    return candidates[0][0] if len(candidates) == 1 else None


def annotation_block_start(lines: list[str], decl_index: int) -> int:
    """方法声明往上找注解块/javadoc 的起点"""
    i = decl_index
    while i > 0:
        prev = lines[i - 1].strip()
        if prev.startswith("@") or prev.startswith("*") or prev.startswith("/*") or prev.endswith("*/"):
            i -= 1
        else:
            break
    return i


JAVADOC_NOISE = re.compile(r"^\s*(/\*\*|\*/|\*)\s*")


def summary_of(lines: list[str], block_start: int, decl_index: int) -> str | None:
    """文案来源优先级：@Operation(summary) > javadoc 首行描述。

    im-server 是 yudao 风格代码，swagger 注解用得少（165 条违例里 145 条没有 summary），
    但普遍有单行 javadoc 如 `/** 坐席接听（买家呼入）。 */`——那同样是开发者写的准确描述。
    """
    for line in lines[block_start:decl_index]:
        m = OPERATION_RE.search(line)
        if m:
            return m.group(1)

    for line in lines[block_start:decl_index]:
        stripped = line.strip()
        if not stripped.startswith(("/**", "*", "*/")):
            continue
        text = JAVADOC_NOISE.sub("", line).replace("*/", "").strip()
        # 跳过 javadoc 标签行（@param/@return/@author…）与空行
        if not text or text.startswith("@"):
            continue
        # 只取第一句：javadoc 常写成「拉黑。幂等：重复 POST 是空操作，不报错。」，
        # 后半句是实现说明，放进审计 title 会误导阅读者。
        text = re.split(r"[。；;]", text)[0].strip()
        # 过滤续行/条件句开头——那是多行 javadoc 的说明部分，不是操作名。
        # 实测踩到的例子：「缺依赖则返回空串…」「且 system 消息在原文案后追加…」
        if text.startswith(("且", "另", "注意", "缺", "若", "当", "但", "其中", "同时", "否则", "该", "此")):
            continue
        # 太长的不适合做审计 title；太短的没信息量
        if 2 <= len(text) <= 24 and '"' not in text and "\\" not in text:
            return text
    return None


def insert_index(lines: list[str], block_start: int, decl_index: int) -> int:
    """插在第一个 mapping 注解之前；没有则插在方法声明前"""
    for i in range(block_start, decl_index):
        if MAPPING_RE.match(lines[i]):
            return i
    return decl_index


def ensure_import(lines: list[str]) -> bool:
    """返回是否可用简名注解。存在同简名 import 时返回 False，调用方须用全限定名。

    踩过的坑：SysLogController 已 import com.zhiflow.upms.common.entity.SysLog
    （日志实体），再 import 注解的 SysLog 会因 single-type-import 同简名冲突而编译失败。
    """
    if any(l.strip() == SYSLOG_IMPORT for l in lines):
        return True
    # 同简名冲突检测
    if any(l.strip().startswith("import ") and l.strip().rstrip(";").endswith(".SysLog")
           and l.strip() != SYSLOG_IMPORT for l in lines):
        return False
    last_import = max((i for i, l in enumerate(lines) if l.startswith("import ")), default=None)
    if last_import is None:
        pkg = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines.insert(pkg + 1, "")
        lines.insert(pkg + 2, SYSLOG_IMPORT)
    else:
        lines.insert(last_import + 1, SYSLOG_IMPORT)
    return True


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    wanted = sys.argv[2:]

    by_file: dict[Path, list[tuple[str, int]]] = defaultdict(list)
    unresolved: list[str] = []
    for raw in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        m = VIOLATION_RE.match(raw.strip())
        if not m:
            continue
        fqcn, method, params = m.group("fqcn"), m.group("method"), m.group("params")
        path = source_path(fqcn)
        if path is None:
            unresolved.append(f"无法定位模块: {raw}")
            continue
        if wanted and not any(str(path).startswith(w) for w in wanted):
            continue
        param_count = 0 if not params.strip() else params.count(",") + 1
        by_file[path].append((method, param_count))

    added = skipped_no_summary = skipped_not_found = 0
    for path, methods in sorted(by_file.items()):
        if not path.exists():
            unresolved.append(f"源文件不存在: {path}")
            continue
        lines = path.read_text(encoding="utf-8").splitlines()
        # 从后往前插，避免行号漂移
        planned = []
        for method, param_count in methods:
            decl = find_method_decl(lines, method, param_count)
            if decl is None:
                unresolved.append(f"定位失败(可能是无法区分的重载): {path.name}#{method}/{param_count}")
                skipped_not_found += 1
                continue
            block = annotation_block_start(lines, decl)
            if any("@SysLog" in lines[i] for i in range(block, decl)):
                continue  # 已有
            summary = summary_of(lines, block, decl)
            if not summary:
                unresolved.append(f"无 @Operation summary，需人工定文案: {path.name}#{method}")
                skipped_no_summary += 1
                continue
            planned.append((insert_index(lines, block, decl), lines[block].split("@")[0], summary))

        for idx, indent_src, summary in sorted(planned, reverse=True):
            indent = re.match(r"[ \t]*", indent_src).group(0) or "\t"
            lines.insert(idx, f'{indent}@SysLog("{summary}")')
            added += 1
        if planned:
            if not ensure_import(lines):
                unresolved.append(f"同简名 import 冲突，已用全限定名: {path.name}")
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"已补 @SysLog: {added}")
    print(f"跳过(无 summary，需人工): {skipped_no_summary}")
    print(f"跳过(定位失败): {skipped_not_found}")
    if unresolved:
        print("\n--- 需人工处理 ---")
        for item in unresolved[:40]:
            print(" ", item)
        if len(unresolved) > 40:
            print(f"  ...共 {len(unresolved)} 条")
    return 0


if __name__ == "__main__":
    sys.exit(main())
