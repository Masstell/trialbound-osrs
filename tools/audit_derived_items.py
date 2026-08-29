#!/usr/bin/env python3
"""Generate (or audit) derived_items.json from the OSRS Wiki's recipe data.

Harvests every recipe (Bucket:Recipe) and the collection log list from the
wiki, computes the transitive closure of "craftable from collection log
items", and emits src/main/resources/derived_items.json.

Rules:
- A product is GATED if every one of its recipes needs at least one gated
  material (clog item or gated intermediate). Products with any recipe free
  of clog materials are never locked (darts, bolts, bars...).
- Requirements are the clog ROOTS of the chain (Echo boots -> Guardian boots
  -> Black tourmaline core), validated against the in-game clog list
  (~/.runelite/trialbound/clog-items.csv, written by the plugin) when
  available, else the wiki's list.
- EXCLUDED_MATERIALS: 100%-drop commodities that would lock huge consumable
  trees (Zulrah's scales, firelighters...). EXCLUDED_PRODUCTS/patterns: POH
  decorations, stuffed heads, sailing paint cosmetics, arena icons.

Usage:
  python tools/audit_derived_items.py           # report only
  python tools/audit_derived_items.py --generate  # rewrite derived_items.json
"""
import json
import re
import sys
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
LOCAL_CLOG_CSV = Path.home() / ".runelite" / "trialbound" / "clog-items.csv"

# Commodity clog materials that would lock big consumable/ammo trees.
EXCLUDED_MATERIALS = {
    "Zulrah's scales", "Sunfire splinters", "Ancient essence", "Oathplate shards",
    "Burnt page", "Gryphon feather", "Smithing catalyst", "Ent branch",
    "Broken antler", "Ray barbs", "Golden tench", "Star fragment",
    "Blue firelighter", "Green firelighter", "Red firelighter",
    "Purple firelighter", "White firelighter", "Granite dust",
    "Araxyte venom sac", "Nihil shard", "Guardian's eye", "Eternal gem",
    "Imbued heart", "Dragon nails", "Dragon metal sheet", "Log brace",
    "Anima-infused bark", "Felling axe handle", "Zalcano shard",
    "Dark dye", "Abyssal blue dye", "Abyssal green dye", "Abyssal red dye",
    "Holy sandals", "Flippers", "Mole slippers",
}

# Recipe material names that differ from the clog entry they represent.
MATERIAL_ALIASES = {
    "Black mask": "Black mask (10)",
}


# Chains the wiki Recipe bucket does not model (enchanting), seeded into the
# fixpoint so downstream recipes (slayer helm recolours etc.) resolve too.
# Also refined clog items (Onyx): the fixpoint skips clog-item products, but
# the plugin needs the recipe so cutting an unlocked Uncut onyx counts as
# unlocked - LockedItemHelper resolves clog-to-clog requirements recursively.
MANUAL_EXTRAS = {
    "Onyx": ["Uncut onyx"],
    "Slayer helmet": ["Black mask (10)"],
    "Slayer helmet (i)": ["Black mask (10)"],
    "Zenyte": ["Zenyte shard"],
    "Zenyte amulet (u)": ["Zenyte shard"],
    "Zenyte amulet": ["Zenyte shard"],
    "Zenyte ring": ["Zenyte shard"],
    "Zenyte necklace": ["Zenyte shard"],
    "Zenyte bracelet": ["Zenyte shard"],
    "Amulet of torture": ["Zenyte shard"],
    "Necklace of anguish": ["Zenyte shard"],
    "Ring of suffering": ["Zenyte shard"],
    "Tormented bracelet": ["Zenyte shard"],
    # Permanent combine, not a charge: needs the Muspah essence slot too.
    "Saturated heart": ["Imbued heart", "Ancient essence"],
    # Uncharged forms whose names don't reduce to the charged product's name,
    # so DerivedItemRegistry's paren-stripping can't reach the recipe.
    "Uncharged toxic trident": ["Magic fang", "Uncharged trident"],
    "Toxic staff (uncharged)": ["Magic fang", "Staff of the Dead"],
    # Mutagens applied to the serpentine helm; the helms share the serpentine
    # variation family but carry their own names.
    "Tanzanite helm": ["Serpentine visage", "Tanzanite mutagen"],
    "Magma helm": ["Serpentine visage", "Magma mutagen"],
    # Blessed quiver and max-cape combines live in variation families with no
    # clog identity of their own.
    "Blessed dizana's quiver": ["Dizana's quiver (uncharged)"],
    "Dizana's max cape": ["Dizana's quiver (uncharged)"],
    "Dizana's max hood": ["Dizana's quiver (uncharged)"],
}

EXCLUDED_PRODUCT_PREFIXES = ("Stuffed ", "Ensouled ", "Greenman ", "Cw armour")
EXCLUDED_PRODUCT_SUFFIXES = (" trim", " coffin", " icon", " paint", "'s flag",
                             " theme", " (Construction)", " (Last Man Standing)",
                             " (Trailblazer)", " (Deadman)")
EXCLUDED_PRODUCTS = {
    "Obsidian fence", "Obsidian decorative bench", "Gnomish firelighter",
    "Molch pearl", "Fathom pearl", "Sturdy harness", "Beehive (Construction)",
    "Anti-venom", "Anti-venom+", "Extended anti-venom+", "Forgotten brew",
    "Sunfire rune", "Jug of sunfire wine", "Searing page", "Infernal blend",
    "Cadantine blood potion (unf)",
    "Godsword shards 1 & 2", "Godsword shards 1 & 3", "Godsword shards 2 & 3",
    "Bone fragments", "Armadylean plate", "Bandosian components", "Nihil dust",
    "Crystal acorn", "Eternal teleport crystal", "Headless arrow",
    "Headless atlatl dart", "Amulet of the Eye", "Hat of the Eye",
    "Robe top of the Eye", "Robe bottoms of the Eye", "Lost bag",
    "Top hat & monocle", "Partyhat & specs", "Pirate hat & patch",
    "Hat eyepatch", "Double eye patch", "Cavalier mask", "Beret mask",
    "Holy moleys", "Dark flippers", "Gem sack", "Fish sack barrel",
    "Silklined herb sack", "Clothes pouch",
}


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


def load_clog_names(wiki_rows):
    """normalized -> display name; prefers the in-game export."""
    if LOCAL_CLOG_CSV.exists():
        names = {}
        for line in LOCAL_CLOG_CSV.read_text(encoding="utf-8").splitlines():
            _, _, name = line.partition(",")
            if name:
                names[normalize(name)] = name
        print(f"Clog list: {len(names)} items (in-game export)")
        return names
    names = {normalize(r["item_name"]): r["item_name"]
             for r in wiki_rows if r.get("item_name")}
    print(f"Clog list: {len(names)} items (wiki; run the plugin once for the in-game export)")
    return names


def excluded_product(page: str) -> bool:
    if page in EXCLUDED_PRODUCTS:
        return True
    if page.startswith(EXCLUDED_PRODUCT_PREFIXES):
        return True
    return any(page.endswith(s) for s in EXCLUDED_PRODUCT_SUFFIXES)


def main():
    generate = "--generate" in sys.argv

    print("Fetching collection log items...")
    wiki_clog = fetch_all("collection_log_source", "'item_id','item_name'")
    clog_names = load_clog_names(wiki_clog)
    excluded_norm = {normalize(m) for m in EXCLUDED_MATERIALS}

    print("Fetching recipes...")
    recipe_rows = fetch_all("recipe", "'page_name','uses_material'")

    # product -> list of recipes (each a list of material names)
    recipes = {}
    for row in recipe_rows:
        page = row.get("page_name")
        materials = row.get("uses_material") or []
        if page and materials:
            recipes.setdefault(page, []).append(materials)

    alias_norm = {normalize(k): v for k, v in MATERIAL_ALIASES.items()}

    def clog_mat_name(name):
        """Display name of the clog item this material represents, or None."""
        n = normalize(name)
        if n in alias_norm:
            aliased = alias_norm[n]
            return aliased if normalize(aliased) in clog_names else None
        if n in clog_names and n not in excluded_norm:
            return clog_names[n]
        return None

    # Fixpoint: requirements[product] = clog roots, only when EVERY recipe of
    # the product needs at least one gated material. Manual extras seed chains
    # the Recipe bucket does not model (enchanting).
    requirements = {}
    for product, reqs in MANUAL_EXTRAS.items():
        valid = [r for r in reqs if normalize(r) in clog_names]
        if valid:
            requirements[product] = set(valid)
        else:
            print(f"WARNING: manual extra '{product}' has no valid clog requirements")
    manual_products = set(MANUAL_EXTRAS)
    changed = True
    while changed:
        changed = False
        for page, page_recipes in recipes.items():
            # Manual seeds are authoritative; everything else is recomputed
            # every pass so requirements GROW as upstream chains resolve
            # (a product must never freeze before its materials are gated).
            if page in manual_products or excluded_product(page) or normalize(page) in clog_names:
                continue
            roots, all_gated = set(), True
            for mats in page_recipes:
                recipe_roots = set()
                for mat in mats:
                    clog_name = clog_mat_name(mat)
                    if clog_name is not None:
                        recipe_roots.add(clog_name)
                    elif mat in requirements:
                        recipe_roots.update(requirements[mat])
                if not recipe_roots:
                    all_gated = False
                    break
                roots.update(recipe_roots)
            if all_gated and roots and requirements.get(page) != roots:
                requirements[page] = roots
                changed = True

    entries = [{"product": page, "requires": sorted(reqs)}
               for page, reqs in sorted(requirements.items())]

    print(f"\nGated products: {len(entries)}")
    if generate:
        data = {
            "version": 2,
            "_comment": "GENERATED by tools/audit_derived_items.py --generate from OSRS Wiki recipe data. "
                        "Products crafted from collection log items are locked while any listed clog item is locked. "
                        "Edit EXCLUDED_* in the script (not this file) and regenerate.",
            "derived": entries,
        }
        DERIVED_FILE.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {DERIVED_FILE}")
    else:
        current = json.loads(DERIVED_FILE.read_text(encoding="utf-8"))
        covered = {normalize(e["product"]) for e in current["derived"]}
        missing = [e for e in entries if normalize(e["product"]) not in covered]
        lines = [f"Would generate {len(entries)} entries; {len(missing)} not in current file:", ""]
        lines += [f"  {e['product']}  <-  {', '.join(e['requires'])}" for e in missing]
        REPORT_FILE.write_text("\n".join(lines), encoding="utf-8")
        print(f"Report written to {REPORT_FILE} ({len(missing)} missing)")


if __name__ == "__main__":
    main()
