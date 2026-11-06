#!/usr/bin/env python3
"""Search factorio.school and fetch blueprints from it, politely and cached.

    python3 tools/blueprints/fetch.py search "green circuit" [--version 2.0] [--n 40]
    python3 tools/blueprints/fetch.py get <key> [<key>...]          # saves <cache>/<key>.json, prints title/votes/tags

factorio.school (also factorioprints.com, the same site) publishes the endpoints its own open-source frontend uses
(github.com/FactorioBlueprints/factorio-prints, src/api): a search API at www.factorio.school/api/... and every
blueprint as static JSON on a CDN (factorio-blueprint-firebase-cdn.pages.dev). Its robots.txt allows all paths for
ordinary user agents (Content-Signal: search=yes, ai-train=no, use=reference); the CDN's has no rules. So: small
numbers, at most one request every 2 seconds, a cache so nothing is fetched twice, an honest User-Agent, nothing
used for training and nothing republished (the cache lives outside the repo; commit only keys and metrics).

The cache defaults to ~/.cache/factorio-optimizer/blueprints (FACTORIO_BP_CACHE overrides). A cached record is the
site's JSON: title, tags, numberOfFavorites, descriptionMarkdown and blueprintString, which analyze.py and
testcopy.py read directly.
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

UA = "factorio-optimizer-benchmark/0.1 (layout metrics study; ~1 request per 2 s; github.com/SeezoCode/factorio_optimizer)"
API = "https://www.factorio.school/api/blueprintSummaries/search/page/{page}?{query}"
CDN = "https://factorio-blueprint-firebase-cdn.pages.dev/{prefix}/{key}.json"
CACHE = os.environ.get("FACTORIO_BP_CACHE", os.path.expanduser("~/.cache/factorio-optimizer/blueprints"))
DELAY = 2.0
_last = [0.0]


def polite_get(url):
    wait = _last[0] + DELAY - time.time()
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    finally:
        _last[0] = time.time()


def version_long(v):
    """'2.0' -> Factorio's 64-bit version number (major << 48 | minor << 32 | patch << 16)."""
    parts = [int(x) for x in v.split(".")] + [0, 0, 0]
    return (parts[0] << 48) | (parts[1] << 32) | (parts[2] << 16)


def search(text, version=None, n=40, page=1, order="Favorites"):
    q = {"pageSize": str(n), "text": text, "orderBy": order}
    if version:
        q["gameVersionLong"] = str(version_long(version))
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "search_" + urllib.parse.quote(json.dumps(q, sort_keys=True), safe="") + ".json")
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    data = polite_get(API.format(page=page, query=urllib.parse.urlencode(q)))
    with open(path, "wb") as f:
        f.write(data)
    return json.loads(data)


def get(key):
    """The site's record for a blueprint key (cached). Returns (path, record)."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, key + ".json")
    if not os.path.exists(path):
        data = polite_get(CDN.format(prefix=key[:3].lower(), key=key))
        with open(path, "wb") as f:
            f.write(data)
    with open(path) as f:
        return path, json.load(f)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("search", help="search titles and descriptions, most favourited first")
    s.add_argument("text")
    s.add_argument("--version", help="game version filter, e.g. 2.0")
    s.add_argument("--n", type=int, default=40)
    s.add_argument("--page", type=int, default=1)
    g = sub.add_parser("get", help="fetch blueprints by key into the cache")
    g.add_argument("keys", nargs="+")
    a = ap.parse_args(argv)
    if a.cmd == "search":
        d = search(a.text, a.version, a.n, a.page)
        p = d["_metadata"]["pagination"]
        print(f"page {p['pageNumber']} of {p['numberOfPages']}")
        for b in d["_data"]:
            print(f"  {b['key']}  {b['voteSummary']['numberOfUpvotes']:5d}  {b['title'][:90]}")
    else:
        for k in a.keys:
            path, r = get(k)
            print(f"{k}  {r.get('numberOfFavorites', 0):4d} favs  {r.get('title', '')[:70]}  {r.get('tags', [])}\n    -> {path}")


if __name__ == "__main__":
    sys.exit(main())
