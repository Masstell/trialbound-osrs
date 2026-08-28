#!/usr/bin/env python3
"""Audit derived_items.json against the OSRS Wiki's recipe data.

Harvests every recipe (Bucket:Recipe) and the authoritative collection log
item list (Bucket:Collection log source) from the wiki, then reports every
craftable product whose materials include a collection log item but which is
not covered by src/main/resources/derived_items.json.

Usage:  python tools/audit_derived_items.py
Writes: tools/derived_audit_report.txt
"""
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://oldschool.runescape.wiki/api.php"
UA = "Trialbound-derived-items-audit/1.0 (github.com/Masstell/trialbound-osrs)"
PAGE_SIZE = 500

ROOT = Path(__file__).resolve().parent.parent
DERIVED_FILE = ROOT / "src" / "main" / "resources" / "derived_items.json"
REPORT_FILE = Path(__file__).resolve().parent / "derived_audit_report.txt"


def normalize(name: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", "", name.lower())).strip()


def bucket_query(query: str):
    url = API + "?" + urllib.parse.urlencode(
        {"action": "bucket", "format": "json", "query": query})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.load(resp)
    if "error" in data:
        raise RuntimeError(f"Bucket error for {query!r}: {data['error']}")
    return data.get("bucket", [])


def fetch_all(bucket: str, fields: str):
    rows, offset = [], 0
    while True:
        page = bucket_query(
            f"bucket('{bucket}').select({fields}).limit({PAGE_SIZE}).offset({offset}).run()")
        rows.extend(page)
        print(f"  {bucket}: {len(rows)} rows")
        if len(page) < PAGE_SIZE:
            return rows
        offset += PAGE_SIZE
        time.sleep(0.5)


def main():
    print("Fetching collection log items...")
    clog_rows = fetch_all("collection_log_source", "'item_id','item_name'")
    clog_names = {normalize(r["item_name"]): r["item_name"]
                  for r in clog_rows if r.get("item_name")}
    print(f"Collection log items: {len(clog_names)}")

    print("Fetching recipes...")
    recipe_rows = fetch_all("recipe", "'page_name','uses_material'")

    # product page -> set of clog material names used by any of its recipes
    products = {}
    for row in recipe_rows:
        page = row.get("page_name")
        materials = row.get("uses_material") or []
        if not page or not materials:
            continue
        clog_mats = {clog_names[normalize(m)] for m in materials
                     if normalize(m) in clog_names}
        if clog_mats:
            products.setdefault(page, set()).update(clog_mats)

    derived = json.loads(DERIVED_FILE.read_text(encoding="utf-8"))
    covered = {normalize(e["product"]) for e in derived["derived"]}

    missing, already = [], []
    for page, mats in sorted(products.items()):
        norm = normalize(page)
        base = normalize(page.split(" (")[0])
        if norm in clog_names:
            continue  # the product is itself a clog item - locked directly
        if norm in covered or base in covered:
            already.append(page)
            continue
        missing.append((page, sorted(mats)))

    lines = [
        f"Derived-items audit vs OSRS Wiki recipe data",
        f"Collection log items (wiki): {len(clog_names)}",
        f"Recipes scanned: {len(recipe_rows)}",
        f"Products using clog materials: {len(products)}",
        f"Covered by derived_items.json: {len(already)}",
        f"NOT covered ({len(missing)}):",
        "",
    ]
    for page, mats in missing:
        lines.append(f"  {page}  <-  {', '.join(mats)}")
    REPORT_FILE.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:6]))
    print(f"\nReport written to {REPORT_FILE}")


if __name__ == "__main__":
    main()
