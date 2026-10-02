#!/usr/bin/env python3
"""CS3 otomatik yama scripti.

  1) classes*.dex -> smali
  2) Sabit domain string'inin ardina DomainStore.read(domain) cagrisi eklenir
  3) Plugin.load(Context) basina Hook.init(this, context) eklenir (ayar popup'i)
  4) smali -> dex, helper.dex yeni classesN.dex olarak pakete eklenir

Gerekenler: java, tools/lib/*.jar (smali + baksmali), helper.dex

Kullanim:
  python patch_cs3.py Provider.cs3 --list
  python patch_cs3.py Provider.cs3 --domain https://eski.com -o Provider.patched.cs3
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

PKG = "Lcom/example/domainpatch"
READ_SIG = PKG + "/DomainStore;->read(Ljava/lang/String;)Ljava/lang/String;"
INIT_SIG = PKG + "/Hook;->init(Ljava/lang/Object;Landroid/content/Context;)V"
INIT0_SIG = PKG + "/Hook;->init(Ljava/lang/Object;)V"
SETTING_SIG = PKG + "/DomainStore;->setting(Ljava/lang/String;)Ljava/lang/String;"
API = "33"

CONST_RE = re.compile(r'^(\s*)(const-string(?:/jumbo)?)\s+([vp]\d+),\s+"(https?://[^"]*)"\s*$')
SUPER_RE = re.compile(r'^\.super\s+(\S+)')
LOAD_CTX_RE = re.compile(r'^\.method\s.*\bload\(Landroid/content/Context;\)V\s*$')
LOAD_NOARG_RE = re.compile(r'^\.method\s.*\bload\(\)V\s*$')
PLUGIN_SUPER_RE = re.compile(r'^L.*/(Base)?Plugin;$')
LOCALS_RE = re.compile(r'^\s*\.(locals|registers)\s+\d+')
PLUGIN_SUPERS = {
    "Lcom/lagradost/cloudstream3/plugins/Plugin;",
    "Lcom/lagradost/cloudstream3/plugins/BasePlugin;",
}


def run(cmd):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.returncode:
        tail = (r.stderr or r.stdout or "")[-1500:]
        sys.exit("HATA: komut basarisiz: " + " ".join(str(c) for c in cmd) + "\n" + tail)
    return r.stdout


def norm(u):
    return u.strip().rstrip("/").lower()


def rd(f):
    return f.read_text(encoding="utf-8", errors="surrogateescape")


def wr(f, text):
    f.write_text(text, encoding="utf-8", errors="surrogateescape")


def candidates(sm_dirs):
    seen = defaultdict(set)
    for sm in sm_dirs:
        for f in sm.rglob("*.smali"):
            for line in rd(f).split("\n"):
                m = CONST_RE.match(line)
                if m:
                    seen[m.group(4)].add(f.name)
    return seen


def print_candidates(seen):
    if not seen:
        print("  (CS3 icinde http/https ile baslayan sabit string bulunamadi)")
        return
    for url in sorted(seen):
        print("  " + url + "   <- " + ", ".join(sorted(seen[url]))[:80])


CONST_ANY_RE = re.compile(r'^(\s*)(const-string(?:/jumbo)?)\s+([vp]\d+),\s+"((?:[^"\\]|\\.)*)"\s*$')
ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", '"': '"', "'": "'", "\\": "\\", "0": "\0"}


def unescape_smali(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", s[i + 2:i + 6]):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(ESC.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def patch_settings(sm, mapping):
    """mapping: {orijinal metin: anahtar}. Eslesen her const-string, DomainStore.setting(orijinal@@@anahtar) sonucuna cevrilir."""
    count = 0
    for f in sm.rglob("*.smali"):
        out, hit = [], False
        for line in rd(f).split("\n"):
            m = CONST_ANY_RE.match(line)
            if m and "@@" not in m.group(4):
                key = mapping.get(unescape_smali(m.group(4)))
                if key:
                    ind, op, reg, lit = m.group(1), m.group(2), m.group(3), m.group(4)
                    out.append(ind + op + " " + reg + ', "' + lit + "@@@" + key + '"')
                    out.append(ind + "invoke-static/range {" + reg + " .. " + reg + "}, " + SETTING_SIG)
                    out.append(ind + "move-result-object " + reg)
                    hit = True
                    count += 1
                    continue
            out.append(line)
        if hit:
            wr(f, "\n".join(out))
    return count


def host_of(d):
    h = re.sub(r"^https?://", "", d.strip(), flags=re.I)
    h = re.sub(r"^www\.", "", h, flags=re.I).split("/")[0].lower()
    return re.sub(r"[^\w.-]", "", h)


def patch_domains(parts, domains, base):
    """Her domain icin ad uretir (tek domain: ad, cok domain: ad:host) ve smali'ye isler."""
    multi = len(domains) > 1
    total, changed, missing, names = 0, set(), [], {}
    for dom in domains:
        nm = (base + ":" + host_of(dom)) if multi else base
        found = 0
        for d, sm in parts:
            n = patch_domain(sm, dom, nm)
            if n:
                found += n
                changed.add(d)
        if found:
            names[dom] = (nm, found)
        else:
            missing.append(dom)
        total += found
    return total, changed, missing, names


def patch_domain(sm, domain, name):
    name = re.sub(r"[^\w.:-]", "", name)
    want = norm(domain)
    count = 0
    for f in sm.rglob("*.smali"):
        out, hit = [], False
        for line in rd(f).split("\n"):
            m = CONST_RE.match(line)
            if m and norm(m.group(4)) == want:
                ind, op, reg, url = m.group(1), m.group(2), m.group(3), m.group(4)
                # eski domain'e eklenti adini ekle: "url@@Ad"
                out.append(ind + op + " " + reg + ', "' + url + "@@" + name + '"')
                out.append(ind + "invoke-static/range {" + reg + " .. " + reg + "}, " + READ_SIG)
                out.append(ind + "move-result-object " + reg)
                hit = True
                count += 1
            else:
                out.append(line)
        if hit:
            wr(f, "\n".join(out))
    return count


def insert_hook(lines, method_re, call):
    out, in_m, done = [], False, False
    for line in lines:
        out.append(line)
        if line.startswith(".method") and method_re.match(line):
            in_m = True
        elif in_m and not done and LOCALS_RE.match(line):
            out.append("    " + call)
            done = True
        elif line.startswith(".end method"):
            in_m = False
    return out, done


def super_of(lines):
    for line in lines[:12]:
        m = SUPER_RE.match(line)
        if m:
            return m.group(1)
    return ""


def patch_hook(sm):
    for f in sorted(sm.rglob("*.smali")):
        text = rd(f)
        lines = text.split("\n")
        sup = super_of(lines)
        if not sup or not (sup in PLUGIN_SUPERS or PLUGIN_SUPER_RE.match(sup)):
            continue
        if INIT_SIG in text or INIT0_SIG in text:
            return True
        # once load(Context), yoksa parametresiz load()
        variants = (
            (LOAD_CTX_RE, "invoke-static/range {p0 .. p1}, " + INIT_SIG),
            (LOAD_NOARG_RE, "invoke-static/range {p0 .. p0}, " + INIT0_SIG),
        )
        for method_re, call in variants:
            out, done = insert_hook(lines, method_re, call)
            if done:
                new_text = "\n".join(out)
                if sup.endswith("/BasePlugin;"):
                    # ayar butonu (openSettings) sadece Plugin'de var: ust sinifi Plugin yap
                    plug = sup[:-len("BasePlugin;")] + "Plugin;"
                    new_text = new_text.replace(".super " + sup, ".super " + plug, 1)
                    new_text = new_text.replace(sup + "-><init>()V", plug + "-><init>()V")
                    print("Ust sinif BasePlugin -> Plugin olarak degistirildi: " + f.name)
                wr(f, new_text)
                return True
    return False


def describe_plugins(parts):
    print("Plugin'e benzeyen siniflar:")
    found = False
    for _, sm in parts:
        for f in sorted(sm.rglob("*.smali")):
            lines = rd(f).split("\n")
            sup = super_of(lines)
            if "Plugin" in sup:
                found = True
                print("  " + f.name + " extends " + sup)
                for l in lines:
                    if l.startswith(".method") and "load" in l:
                        print("      " + l)
    if not found:
        print("  (Plugin'den tureyen sinif bulunamadi)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cs3")
    ap.add_argument("--domain", action="append", help="CS3 icinde yazili ESKI domain (birden cok kez verilebilir)")
    ap.add_argument("--name", help="Eklenti adi (varsayilan: dosya adi)")
    ap.add_argument("--settings", help="Ayarlanabilir sabitler: [{\"key\":..,\"value\":..}] JSON dosyasi")
    ap.add_argument("--list", action="store_true", help="bulunan URL'leri listele")
    ap.add_argument("--helper", default="helper.dex")
    ap.add_argument("--tools", default="tools")
    ap.add_argument("-o", "--out")
    a = ap.parse_args()

    cp = a.tools + "/lib/*"
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        x = tmp / "x"
        with zipfile.ZipFile(a.cs3) as z:
            z.extractall(x)

        dexes = sorted(p for p in x.iterdir() if re.fullmatch(r"classes\d*\.dex", p.name))
        if not dexes:
            sys.exit("HATA: CS3 icinde classes.dex yok (dosya gecerli bir CS3 degil)")

        parts = []
        for i, d in enumerate(dexes):
            sm = tmp / ("sm%d" % i)
            run(["java", "-cp", cp, "org.jf.baksmali.Main", "d", d, "-a", API, "-o", sm])
            parts.append((d, sm))

        seen = candidates([sm for _, sm in parts])
        if a.list:
            print("Bulunan URL sabitleri:")
            print_candidates(seen)
            return
        domains = a.domain or []
        if not domains:
            sys.exit("HATA: --domain gerekli (once --list ile bak)")

        name = re.sub(r"[^\w.-]", "", a.name or Path(a.cs3).stem) or "plugin"
        total, changed, missing, names = patch_domains(parts, domains, name)
        for dom, (nm, cnt) in names.items():
            print("  %s -> ad: %s (%d yer)" % (dom, nm, cnt))
        if total == 0:
            print("Verilen domain(ler): " + ", ".join(domains))
            print("CS3 icinde bulunan URL sabitleri:")
            print_candidates(seen)
            sys.exit("HATA: Domain string'i bulunamadi. Yukaridaki listeden birebir yaz.")
        if missing:
            print("UYARI: bulunamayan domain(ler): " + ", ".join(missing))

        if a.settings:
            try:
                mapping = {x["value"]: x["key"] for x in json.loads(Path(a.settings).read_text(encoding="utf-8"))}
            except Exception as e:
                sys.exit("HATA: --settings dosyasi okunamadi: " + str(e))
            nset = 0
            for d, sm in parts:
                n = patch_settings(sm, mapping)
                if n:
                    nset += n
                    changed.add(d)
            print("AYAR: %d yerde, %d anahtar ayarlanabilir yapildi" % (nset, len(mapping)))

        hooked = False
        for d, sm in parts:
            if patch_hook(sm):
                changed.add(d)
                hooked = True
                break
        if not hooked:
            describe_plugins(parts)
            sys.exit("HATA: Plugin.load() / load(Context) bulunamadi; popup hook'u eklenemedi.")

        for d, sm in parts:
            if d in changed:
                run(["java", "-cp", cp, "org.jf.smali.Main", "a", sm, "-a", API, "-o", d])

        n = len(dexes) + 1
        while (x / ("classes%d.dex" % n)).exists():
            n += 1
        shutil.copy(a.helper, x / ("classes%d.dex" % n))

        out = Path(a.out) if a.out else Path(a.cs3).with_suffix(".patched.cs3")
        out.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for p in sorted(x.rglob("*")):
                if p.is_file():
                    z.write(p, p.relative_to(x).as_posix())
        print("TAMAM: %d yerde, %d domain yerlestirildi (ad: %s), popup hook eklendi -> %s" % (total, len(names), name, out))


if __name__ == "__main__":
    main()
