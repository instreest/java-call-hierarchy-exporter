#!/usr/bin/env bash
# README の日本語側と英語側の食い違い、リンク切れを検出する。
#
#   bash test/readme/run.sh
#
# README は日本語が先、その下（"# English" 以降）に同じ内容の英語を置く対訳である（AGENTS.md の
# 「ドキュメントの決まり」）。片方だけ直すと黙って食い違うので、次を機械で確かめる。
#   - 節の印（見出しの直前の <!-- sec:ID -->）が両側で同じ並びにあり、どれも見出しの直前にある
#   - 節ごとに、表の行・コードブロック・箇条の数と、リンク先のファイルの集合が両側でそろう
#   - README の中のアンカー（#…）がすべて解決し、見出しのスラッグが重ならない（GitHub が -1 を付ける）
#   - docs/ から README への、README から docs/ へのアンカー付きのリンクが解決する
#   - README に書いた JDT の版が //DEPS の版と同じ
# ファイルの中身を読むだけなので、JDK もネットワークも要らない。
set -uo pipefail
cd "$(dirname "$0")/../.."
python3 - <<'PY'
import re, sys, glob, os, unicodedata

fails = []
def ok(msg): print(f"  OK   {msg}")
def ng(msg): print(f"  NG   {msg}"); fails.append(msg)

text = open('README.md', encoding='utf-8').read()
cut = text.index('\n# English\n') + 1
halves = {'ja': text[:cut], 'en': text[cut:]}

def strip_code(t):
    return re.sub(r'```.*?```', '', t, flags=re.S)

def slug(h):
    return ''.join('-' if ch in ' -' else ch for ch in h.strip().lower()
                   if ch in ' -_' or unicodedata.category(ch)[0] in 'LNM')

def anchors(path):
    res, out = {}, set()
    for h in re.findall(r'^#{1,6} (.*)$', strip_code(open(path, encoding='utf-8').read()), re.M):
        a = slug(h); n = res.get(a, 0); res[a] = n + 1
        out.add(a if n == 0 else f'{a}-{n}')
    return out, res

def sections(t):
    """節の印で区切った [(ID, 本文)]。印が見出しの直前に無ければ NG にする"""
    lines = t.split('\n'); out = []; cur = None
    for k, l in enumerate(lines):
        m = re.match(r'<!-- sec:([a-z0-9-]+) -->$', l)
        if m:
            if k + 1 >= len(lines) or not re.match(r'#{2,3} ', lines[k + 1]):
                ng(f"節の印 {m.group(1)} の直後が見出しではない")
            cur = [m.group(1), '']; out.append(cur)
        elif cur is not None:
            cur[1] += l + '\n'
    return out

# 1. 節の並び
secs = {k: sections(v) for k, v in halves.items()}
ids = {k: [s[0] for s in v] for k, v in secs.items()}
if ids['ja'] == ids['en'] and ids['ja']:
    ok(f"節の印が日本語側と英語側で同じ並び（{len(ids['ja'])} 節）")
else:
    ng(f"節の印の並びが違う: ja={ids['ja']} en={ids['en']}")
for k, t in halves.items():
    fence = False; n = 0
    for l in t.split('\n'):
        if l.startswith('```'): fence = not fence
        if not fence and re.match(r'#{2,3} ', l): n += 1
    if n != len(ids[k]):
        ng(f"{k}: 節の印の無い見出しがある（見出し {n} / 印 {len(ids[k])}）")

# 2. 節ごとの中身の形
def shape(body):
    code = body.count('```') // 2
    plain = strip_code(body)
    rows = len(re.findall(r'^\|', plain, re.M))
    items = len(re.findall(r'^\s*(?:[-*]|\d+\.) ', plain, re.M))
    files = set(p.split('#')[0] for p in re.findall(r'\]\(([^)\s]+)\)', plain)
                if not p.startswith('#') and not p.startswith('http'))
    return rows, code, items, files
bad = 0
for (i, bj), (_, be) in zip(secs['ja'], secs['en']):
    sj, se = shape(bj), shape(be)
    if sj != se:
        bad += 1
        ng(f"節 {i} の形が違う（表の行・コード・箇条・リンク先）: ja={sj[:3]} {sorted(sj[3])} / en={se[:3]} {sorted(se[3])}")
if not bad:
    ok("節ごとの表の行・コードブロック・箇条・リンク先のファイルがそろう")

# 3. README の中のアンカーと見出しの重なり
own, counts = anchors('README.md')
dups = [a for a, n in counts.items() if n > 1]
if dups: ng(f"見出しのスラッグが重なる: {dups}")
else: ok("見出しのスラッグが重ならない")

def check_links(path, body):
    broken = []
    base = os.path.dirname(path)
    for t in re.findall(r'\]\(([^)\s]+)\)', strip_code(body)):
        if t.startswith(('http', 'mailto')): continue
        p, _, frag = t.partition('#')
        target = path if p == '' else os.path.normpath(os.path.join(base, p))
        if not os.path.exists(target): broken.append(t); continue
        if frag and target.endswith('.md') and frag not in anchors(target)[0]:
            broken.append(t)
    return broken

broken = check_links('README.md', text)
if broken: ng(f"README のリンク切れ: {broken}")
else: ok("README のリンク（アンカー付きの docs へのリンクを含む）がすべて解決する")

# 4. docs/ から README へ
broken = []
for f in sorted(glob.glob('docs/*.md')):
    body = open(f, encoding='utf-8').read()
    for t in re.findall(r'\]\((\.\./README\.md#[^)\s]+)\)', strip_code(body)):
        if t.split('#', 1)[1] not in own: broken.append(f"{f}: {t}")
if broken: ng(f"docs から README へのリンク切れ: {broken}")
else: ok("docs/ から README の節へのリンクがすべて解決する")

# 5. JDT の版
deps = re.search(r'org\.eclipse\.jdt\.core:([0-9.]+)', open('src/jche/CallHierarchyExporter.java', encoding='utf-8').read()).group(1)
found = set(re.findall(r'JDT[^|\n]*?([0-9]+\.[0-9]+\.[0-9]+)', text))
if found and found == {deps}: ok(f"README の JDT の版が //DEPS と同じ（{deps}）")
else: ng(f"README の JDT の版 {sorted(found)} が //DEPS の {deps} と違う")

print("FAIL" if fails else "PASS")
sys.exit(1 if fails else 0)
PY
