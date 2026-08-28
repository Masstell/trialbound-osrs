"""Generate shop_items.json: the collection-log items purchasable from shops.

These are the ONLY items the possession-gain path may unlock without a
correlated kill (see ShopAcquisitionService). Everything else requires a
real loot event or a kill credit for its clog page.

Data source: wiki Bucket 'storeline' (one row per item per store),
intersected with the clog item list (prefers the in-game export at
~/.runelite/trialbound/clog-items.csv). Reward interfaces that the wiki
does not model as stores (e.g. Barbarian Assault) are added via
MANUAL_EXTRAS. Rerun with:  python tools/generate_shop_items.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from audit_derived_items import fetch_all, normalize, load_clog_names, LOCAL_CLOG_CSV

OUT_FILE = Path(__file__).parent.parent / "src" / "main" / "resources" / "shop_items.json"

# Reward interfaces the wiki does not model as stores.
MANUAL_EXTRAS = {
    "Fighter torso": "Barbarian Assault rewards",
    "Fighter hat": "Barbarian Assault rewards",
    "Ranger hat": "Barbarian Assault rewards",
    "Healer hat": "Barbarian Assault rewards",
    "Runner hat": "Barbarian Assault rewards",
    "Penance skirt": "Barbarian Assault rewards",
    "Runner boots": "Barbarian Assault rewards",
    "Penance gloves": "Barbarian Assault rewards",
}

# storeline rows to ignore: stores that merely RESELL player items or
# whose "purchase" is actually gameplay (keep this list short and audited).
EXCLUDED_STORES = set()


def main():
    print("Fetching collection log items...")
    wiki_clog = [] if LOCAL_CLOG_CSV.exists() else fetch_all(
        "collection_log_source", "'item_id','item_name'")
    clog_names = load_clog_names(wiki_clog)  # normalized -> display

    ids_by_norm = {}
    if LOCAL_CLOG_CSV.exists():
        for line in LOCAL_CLOG_CSV.read_text(encoding="utf-8").splitlines():
            item_id, _, name = line.partition(",")
            if name:
                ids_by_norm[normalize(name)] = int(item_id)
    else:
        for r in wiki_clog:
            if r.get("item_name"):
                ids_by_norm[normalize(r["item_name"])] = int(r["item_id"])

    print("Fetching store lines...")
    rows = fetch_all("storeline", "'sold_item','sold_by','store_currency'")

    shops = {}  # normalized item -> {name, id, soldBy set}
    for r in rows:
        item = r.get("sold_item") or ""
        store = r.get("sold_by") or "?"
        if store in EXCLUDED_STORES:
            continue
        norm = normalize(item)
        if norm not in clog_names:
            continue
        entry = shops.setdefault(norm, {
            "name": clog_names[norm],
            "id": ids_by_norm.get(norm),
            "soldBy": set(),
        })
        entry["soldBy"].add(store)

    for name, store in MANUAL_EXTRAS.items():
        norm = normalize(name)
        if norm not in clog_names:
            print(f"  WARNING: manual extra {name!r} is not a clog item")
            continue
        entry = shops.setdefault(norm, {
            "name": clog_names[norm],
            "id": ids_by_norm.get(norm),
            "soldBy": set(),
        })
        entry["soldBy"].add(store)

    missing_ids = [e["name"] for e in shops.values() if e["id"] is None]
    if missing_ids:
        print(f"  WARNING: no item id for: {missing_ids}")

    items = sorted(
        ({"id": e["id"], "name": e["name"], "soldBy": sorted(e["soldBy"])}
         for e in shops.values() if e["id"] is not None),
        key=lambda e: e["name"])

    OUT_FILE.write_text(json.dumps(
        {"version": 1, "items": items}, indent=2) + "\n", encoding="utf-8")
    print(f"\nWrote {len(items)} shop-purchasable clog items to {OUT_FILE}")
    for e in items:
        print(f"  {e['name']}  <-  {', '.join(e['soldBy'])}")


if __name__ == "__main__":
    main()
