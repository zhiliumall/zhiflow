#!/usr/bin/env python3
"""把 ArchUnit baseline 导出为按模块分组的违例清单，供后续子任务（S3/S6/S8/S9）当工作清单用。

store 里的文件名是 UUID，会随规则描述变化而改变，所以这里通过 stored.rules 的规则描述
反查 UUID，而不是硬编码文件名。

用法（在 zhiflow-arch-test 目录下）：
    python3 export-violations.py <输出目录>
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

STORE = Path("src/test/resources/archunit_store")

# 规则描述特征子串 -> (输出文件名, 消费方)
RULES = [
    ("@SysLog", "violations-syslog.txt", "S3 SysLog 全量补齐"),
    ("@PreAuthorize", "violations-preauthorize.txt", "S3 权限声明补齐"),
    ("@Transactional", "violations-transactional.txt", "S9 外键与级联事务"),
    ("removeById", "violations-physical-delete.txt", "S8 软删统一改造"),
    ("BatchResult", "violations-batch-result.txt", "S6 批量 BatchResult 改造"),
    ("ErrorLogRecorder", "violations-swallowed-exceptions.txt", "S12 吞异常补 ErrorLogRecorder"),
]

# 类全名 -> 所属模块，用包名前缀判断
MODULE_BY_PACKAGE = [
    ("com.zhiflow.upms.", "zhiflow-upms-admin"),
    ("com.zhiflow.mall.admin.", "zhiflow-mall-admin"),
    ("com.zhiflow.mall.api.", "zhiflow-mall-api"),
    ("com.zhiflow.im.", "zhiflow-im-server"),
    ("com.zhiflow.live.", "zhiflow-live-admin"),
    ("com.zhiflow.pay.", "zhiflow-pay-admin"),
    ("com.zhiflow.weixin.", "zhiflow-weixin-admin"),
    ("com.zhiflow.auth.", "zhiflow-auth"),
    ("com.zhiflow.codegen.", "zhiflow-codegen"),
]


def unescape_properties_key(raw: str) -> str:
    """还原 java.util.Properties 写出的 key：\\ 转义 + \\uXXXX"""
    text = re.sub(r"\\(?![u])", "", raw)
    return re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), text)


def load_rule_index() -> dict[str, str]:
    """规则描述 -> UUID"""
    index = {}
    for line in (STORE / "stored.rules").read_text(encoding="latin-1").splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        key, uuid = line.rsplit("=", 1)
        index[unescape_properties_key(key)] = uuid.strip()
    return index


def module_of(line: str) -> str:
    for prefix, module in MODULE_BY_PACKAGE:
        if prefix in line:
            return module
    return "(未归类)"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    out_dir = Path(sys.argv[1])
    out_dir.mkdir(parents=True, exist_ok=True)
    index = load_rule_index()

    for marker, filename, consumer in RULES:
        matched = [(desc, uuid) for desc, uuid in index.items() if marker in desc]
        if len(matched) != 1:
            print(f"!! 规则 [{marker}] 在 stored.rules 里匹配到 {len(matched)} 条，跳过")
            continue
        desc, uuid = matched[0]
        violations = [
            line for line in (STORE / uuid).read_text(encoding="utf-8").splitlines() if line.strip()
        ]

        grouped = defaultdict(list)
        for line in violations:
            grouped[module_of(line)].append(line)

        body = [
            f"# {marker} 违例清单（ArchUnit baseline 导出）",
            f"# 消费方：{consumer}",
            f"# 规则：{desc}",
            f"# 总计：{len(violations)} 条",
            "#",
            "# 本文件由 zhiflow-arch-test/export-violations.py 生成，勿手工编辑。",
            "# 修掉违例后重跑门禁，baseline 会自动收缩，再重新导出即可。",
            "",
        ]
        for module in sorted(grouped, key=lambda m: (-len(grouped[m]), m)):
            body.append(f"## {module}（{len(grouped[module])} 条）")
            body.extend(sorted(grouped[module]))
            body.append("")

        (out_dir / filename).write_text("\n".join(body), encoding="utf-8")
        print(f"{len(violations):>4} 条 -> {out_dir / filename}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
