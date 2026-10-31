#!/usr/bin/env python3
"""One-time merge: download a TVBox/FongMi config (e.g. 饭太硬), decode it, append our sites.

Usage: merge_once.py <source_url> <our_config.json> <our_jar_url> <out.json>

Handles the common config encodings used by TVBox hosts (same rules as FongMi's Decoder):
plain JSON (with // comments), base64 hidden after "**" in an image, and the "2423..." AES-CBC hex format.
Relative "./xxx" paths are rewritten to absolute URLs so the merged file can live anywhere.
"""
import base64
import json
import re
import sys
import urllib.parse
import urllib.request

UA = "okhttp/3.15"


def ascii_url(url):
    """Encodes internationalized domain names (e.g. www.饭太硬.net) with IDNA/punycode."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname.encode("idna").decode()
    netloc = host + (":%d" % parts.port if parts.port else "")
    path = urllib.parse.quote(parts.path, safe="/%")
    return urllib.parse.urlunsplit((parts.scheme, netloc, path, parts.query, parts.fragment))


def fetch(url):
    url = ascii_url(url)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(), r.geturl()


def aes_cbc(data_hex):
    from Crypto.Cipher import AES  # pycryptodome
    decode = bytes.fromhex(data_hex).decode("utf-8", "ignore")
    key = decode[decode.index("$#") + 2:decode.index("#$")]
    key = (key + "0" * 16)[:16].encode()
    iv = (data_hex[-13:] + "0" * 16)[:16].encode()
    content = data_hex[data_hex.index("2324") + 4:len(data_hex) - 26]
    out = AES.new(key, AES.MODE_CBC, iv).decrypt(bytes.fromhex(content))
    return out[:-out[-1]].decode("utf-8")


def decode(raw):
    text = strip_comments(raw.decode("utf-8", "ignore")).strip()
    if text.startswith("{"):
        return text
    m = re.search(r"[A-Za-z0-9]{8}\*\*", text)
    if m:
        b64 = text[m.end():].strip()
        return decode(base64.b64decode(b64 + "=" * (-len(b64) % 4)))
    if text.startswith("2423"):
        return aes_cbc(text)
    try:
        return decode(base64.b64decode(text + "=" * (-len(text) % 4)))
    except Exception:
        raise SystemExit("无法识别的配置格式: " + text[:80])


def strip_comments(text):
    out = []
    for line in text.splitlines():
        if line.strip().startswith("//"):
            continue
        out.append(line)
    return "\n".join(out)


def main():
    src, ours_path, jar_url, out_path = sys.argv[1:5]
    run(src, ours_path, jar_url, out_path)


def run(src, ours_path, jar_url, out_path):
    raw, final_url = fetch(src)
    text = decode(raw)
    base = final_url.rsplit("/", 1)[0] + "/"
    text = re.sub(r'"\./', '"' + base, text)
    cfg = json.loads(text)
    absolutize(cfg, base)
    if "urls" in cfg and "sites" not in cfg:
        # The source is itself a multi-repo list: merge into its first repo.
        return run(cfg["urls"][0]["url"], ours_path, jar_url, out_path)
    merge(cfg, ours_path, jar_url, out_path, src)


REL = re.compile(r"^[\w\-./%]+\.(txt|json|js|py)$")


def absolutize(cfg, base):
    """Rewrites bare relative file paths inside site "ext" objects (e.g. "tvfan/Cloud-drive.txt")."""
    for site in cfg.get("sites", []):
        ext = site.get("ext")
        if isinstance(ext, dict):
            for k, v in ext.items():
                if isinstance(v, str) and "://" not in v and REL.match(v):
                    ext[k] = urllib.parse.urljoin(base, v)


def merge(cfg, ours_path, jar_url, out_path, src):
    with open(ours_path, encoding="utf-8") as f:
        ours = json.load(f)
    mine = []
    for s in ours["sites"]:
        s = dict(s)
        s["jar"] = jar_url
        mine.append(s)
    keys = {s["key"] for s in mine}
    sites = [s for s in cfg.get("sites", []) if s.get("key") not in keys]
    cfg["sites"] = mine + sites
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=1)
    print(f"merged {len(mine)} + {len(sites)} sites from {src} -> {out_path}")


if __name__ == "__main__":
    main()
