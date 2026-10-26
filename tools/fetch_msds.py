#!/usr/bin/env python3
"""从 PubChem 拉取试剂的公开安全数据，作为编写 MSDS 示例文本的事实来源。

PubChem 由美国 NCBI/NLM 维护，属美国政府作品，核心数据可自由使用与复制；
脚本保留 CID、来源 URL 和抓取时间，便于逐条核对。第三方贡献的子数据集可能另有
许可，因此这里只抽取安全性描述，不搬运完整的供应商 SDS 原文。

用法（在 hazmat-ledger 目录下）：
    python tools/fetch_msds.py                  # 抓取种子数据里的全部试剂
    python tools/fetch_msds.py 67-56-1          # 只抓一个 CAS
    python tools/fetch_msds.py --out build/msds-sources
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

PUG_REST = "https://pubchem.ncbi.nlm.nih.gov/rest/pug"
PUG_VIEW = "https://pubchem.ncbi.nlm.nih.gov/rest/pug_view/data"
UA = {"User-Agent": "hazmat-ledger-demo/1.0 (MSDS sample data fetch)"}

# Safety and Hazards 下的二级章节，按 MSDS 用途归类
SAFETY_SECTIONS = [
    "Hazards Identification",
    "First Aid Measures",
    "Fire Fighting",
    "Accidental Release Measures",
    "Handling and Storage",
    "Exposure Control and Personal Protection",
    "Stability and Reactivity",
    "Transport Information",
    "Regulatory Information",
    "Other Safety Information",
]

PHYSICAL_SECTIONS = [
    "Physical Description",
    "Color/Form",
    "Odor",
    "Boiling Point",
    "Melting Point",
    "Flash Point",
    "Solubility",
    "Density",
    "Vapor Pressure",
    "Autoignition Temperature",
    "Decomposition",
]


def get_json(url: str) -> dict:
    request = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def cid_for(cas: str) -> int:
    data = get_json(f"{PUG_REST}/compound/name/{cas}/cids/JSON")
    return data["IdentifierList"]["CID"][0]


def view(cid: int, heading: str) -> dict:
    url = f"{PUG_VIEW}/compound/{cid}/JSON?heading={urllib.parse.quote(heading)}"
    return get_json(url)


def collect_text(section: dict) -> str:
    parts: list[str] = []
    for info in section.get("Information", []):
        for item in info.get("Value", {}).get("StringWithMarkup", []):
            text = (item.get("String") or "").strip()
            if text:
                parts.append(text)
    for child in section.get("Section", []):
        parts.append(collect_text(child))
    return " ".join(p for p in parts if p)


def child_sections(record: dict) -> dict[str, str]:
    """递归收集所有层级的章节。理化特性在第三级（Experimental Properties 下）。"""
    result: dict[str, str] = {}

    def walk(section: dict) -> None:
        heading = section.get("TOCHeading", "")
        body = collect_text(section)
        if heading and body:
            result[heading] = " ".join(body.split())
        for child in section.get("Section", []):
            walk(child)

    for top in record.get("Section", []):
        walk(top)
    return result


def fetch_one(cas: str) -> dict:
    cid = cid_for(cas)
    safety = child_sections(view(cid, "Safety and Hazards")["Record"])
    physical = child_sections(view(cid, "Chemical and Physical Properties")["Record"])

    return {
        "cas": cas,
        "cid": cid,
        "source": f"https://pubchem.ncbi.nlm.nih.gov/compound/{cid}",
        "retrieved": date.today().isoformat(),
        "safety": {k: safety[k] for k in SAFETY_SECTIONS if k in safety},
        "physical": {k: physical[k] for k in PHYSICAL_SECTIONS if k in physical},
    }


def read_cas_list(seed: Path) -> list[str]:
    chemicals = json.loads(seed.read_text(encoding="utf-8"))
    return [item["casNo"] for item in chemicals]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cas", nargs="*", help="只抓指定 CAS，不传则读种子数据")
    parser.add_argument("--out", default="build/msds-sources", help="输出目录")
    args = parser.parse_args()

    cas_list = args.cas or read_cas_list(Path("src/main/resources/seed/chemicals.json"))
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    for cas in cas_list:
        target = out_dir / f"{cas}.json"
        try:
            data = fetch_one(cas)
            target.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"{cas} -> CID {data['cid']}, {target} "
                  f"({len(data['safety'])} safety / {len(data['physical'])} physical sections)")
        except Exception as exc:  # 单个试剂失败不阻塞其余
            print(f"{cas} FAILED: {exc}")
        time.sleep(0.25)


if __name__ == "__main__":
    main()
