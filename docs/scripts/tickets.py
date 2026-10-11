#!/usr/bin/env python3
"""DopaGuard file-based issue tracker (Python standard library only)."""
from __future__ import annotations
import argparse
from datetime import date
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TICKETS = ROOT / 'tickets'
STATES = ('backlog', 'ready', 'in-progress', 'blocked', 'review', 'done')
HEADER_RE = re.compile(r'^---\n(.*?)\n---\n', re.S)


def parse_list(raw: str) -> list[str]:
    raw = raw.strip()
    if not raw.startswith('[') or not raw.endswith(']'):
        raise ValueError(f'expected [a, b] list: {raw}')
    return [x.strip() for x in raw[1:-1].split(',') if x.strip()]


def parse(path: Path) -> dict:
    src = path.read_text(encoding='utf-8')
    m = HEADER_RE.match(src)
    if not m:
        raise ValueError(f'front matter missing: {path}')
    fields = {}
    for line in m.group(1).splitlines():
        if ':' not in line: raise ValueError(f'bad front matter line in {path}: {line}')
        k,v = line.split(':',1)
        fields[k.strip()] = v.strip()
    for k in ('depends_on','requirements','acceptance'):
        fields[k] = parse_list(fields.get(k,'[]'))
    fields['path'] = path
    fields['body'] = src[m.end():]
    return fields


def all_tickets() -> dict[str,dict]:
    items={}
    for path in sorted(TICKETS.glob('*/DG-*.md')):
        if path.parent.name not in STATES: continue
        t=parse(path)
        if t['id'] in items:
            raise ValueError('duplicate ticket ID: '+t['id'])
        items[t['id']]=t
    return items


def validate(items:dict[str,dict]) -> list[str]:
    errors=[]
    for tid,t in items.items():
        for k in ('title','priority','status','milestone','estimate','owner','updated'):
            if not t.get(k):errors.append(f'{tid}: missing {k}')
        if t['status'] not in STATES: errors.append(f'{tid}: invalid status {t["status"]}')
        if t['path'].parent.name != t['status']: errors.append(f'{tid}: directory/status mismatch')
        if t['path'].stem != tid: errors.append(f'{tid}: filename mismatch')
        for d in t['depends_on']:
            if d not in items: errors.append(f'{tid}: unknown dependency {d}')
            if d == tid: errors.append(f'{tid}: self dependency')
        if t['status']=='done':
            errors.extend(done_errors(t,items))
    # dependency cycle check
    visiting=set(); visited=set()
    def dfs(x):
        if x in visiting:
            errors.append(f'{x}: dependency cycle');return
        if x in visited:return
        visiting.add(x)
        for d in items[x]['depends_on']:
            if d in items:dfs(d)
        visiting.remove(x);visited.add(x)
    for x in items:dfs(x)
    return errors


def checkboxes(body:str) -> list[str]:
    section=body.split('## 受け入れ条件',1)
    if len(section)==1:return ['受け入れ条件セクションがありません']
    content=section[1].split('\n## ',1)[0]
    found=re.findall(r'^- \[([ xX])\] ',content,re.M)
    if not found:return ['受け入れチェックボックスがありません']
    return ['未チェックの受け入れ条件があります'] if any(c==' ' for c in found) else []


def done_errors(t,items):
    errors=[f'{t["id"]}: {err}' for err in checkboxes(t['body'])]
    for dep in t['depends_on']:
        if dep in items and items[dep]['status']!='done':
            errors.append(f'{t["id"]}: dependency {dep} not done')
    if '## 検証記録' not in t['body']:
        errors.append(f'{t["id"]}: 検証記録がありません')
    else:
        section=t['body'].split('## 検証記録',1)[1].split('\n## ',1)[0].strip()
        if not section or section in ('- 未実施','未実施'):
            errors.append(f'{t["id"]}: 検証記録が未実施のままです')
    return errors


def replace_status(t:dict,new:str,owner:str|None):
    src=t['path'].read_text(encoding='utf-8')
    src=re.sub(r'(?m)^status: .*$',f'status: {new}',src,count=1)
    src=re.sub(r'(?m)^updated: .*$',f'updated: {date.today().isoformat()}',src,count=1)
    if owner:
        if ':' in owner or '\n' in owner:raise ValueError('owner cannot contain colon or newline')
        src=re.sub(r'(?m)^owner: .*$',f'owner: {owner}',src,count=1)
    target=TICKETS/new/(t['id']+'.md')
    if target.exists() and target!=t['path']:raise ValueError(f'target exists: {target}')
    target.write_text(src,encoding='utf-8')
    if target!=t['path']:t['path'].unlink()


def board_text(items):
    by_status={s:[] for s in STATES}
    for tid,t in items.items():by_status[t['status']].append(t)
    lines=['# DopaGuard チケットボード','', '> このファイルは `python docs/scripts/tickets.py board` で自動生成。直接編集しない。','',f'合計 **{len(items)}** 件 / P0 **{sum(t["priority"]=="P0" for t in items.values())}** 件','']
    for status in STATES:
        ts=sorted(by_status[status],key=lambda t:(t['priority'],t['id']))
        lines.append(f'## {status} ({len(ts)})')
        lines.append('')
        if ts:
            lines.append('| ID | 優先 | マイルストーン | タイトル | 依存 | 担当 |')
            lines.append('|---|---|---|---|---|---|')
            for t in ts:
                rel=t['path'].relative_to(TICKETS).as_posix()
                lines.append(f'| [{t["id"]}]({rel}) | {t["priority"]} | {t["milestone"]} | {t["title"]} | {", ".join(t["depends_on"]) or "-"} | {t["owner"]} |')
        else:lines.append('_該当なし_')
        lines.append('')
    return '\n'.join(lines)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('list');q.add_argument('--priority',choices=('P0','P1','P2'))
    q=sub.add_parser('show');q.add_argument('id')
    q=sub.add_parser('move');q.add_argument('id');q.add_argument('status',choices=STATES);q.add_argument('--owner');q.add_argument('--force',action='store_true',help='dependency pending for ready/in-progress only; record reason in ticket')
    sub.add_parser('next');sub.add_parser('board');sub.add_parser('validate')
    args=p.parse_args()
    try:
        items=all_tickets()
        if args.cmd=='validate':
            errors=validate(items)
            print('\n'.join(errors) if errors else f'OK: {len(items)} tickets valid')
            return 1 if errors else 0
        if args.cmd=='board':
            (TICKETS/'BOARD.md').write_text(board_text(items),encoding='utf-8')
            print('Wrote '+str(TICKETS/'BOARD.md'))
            return 0
        if args.cmd=='list':
            for t in sorted(items.values(),key=lambda x:(x['priority'],x['id'])):
                if args.priority and t['priority']!=args.priority:continue
                print(f'{t["id"]:7} {t["priority"]:2} {t["status"]:11} {t["title"]}')
            return 0
        if args.cmd=='next':
            result=[]
            for t in sorted(items.values(),key=lambda x:(x['priority'],x['id'])):
                if t['status'] not in ('backlog','ready'):continue
                if all(items[x]['status']=='done' for x in t['depends_on']):result.append(t)
            for t in result:print(f'{t["id"]:7} {t["priority"]} {t["title"]} ({t["estimate"]})')
            if not result:print('No unblocked backlog/ready tickets')
            return 0
        if args.id not in items:raise ValueError('unknown ticket ID: '+args.id)
        t=items[args.id]
        if args.cmd=='show':
            print(t['path'].read_text(encoding='utf-8'));return 0
        if args.cmd=='move':
            if args.status in ('ready','in-progress') and not args.force:
                blockers=[x for x in t['depends_on'] if items[x]['status']!='done']
                if blockers:raise ValueError('unfinished dependencies: '+', '.join(blockers)+' (use --force ONLY with documented reason)')
            if args.status=='done':
                errors=done_errors(t,items)
                if errors:raise ValueError('; '.join(errors))
            replace_status(t,args.status,args.owner)
            print(f'Moved {args.id} → {args.status}')
            print('Updated '+str(TICKETS/'BOARD.md'))
            new_items=all_tickets()
            (TICKETS/'BOARD.md').write_text(board_text(new_items),encoding='utf-8')
            return 0
    except (ValueError,OSError) as e:
        print('ERROR: '+str(e),file=sys.stderr)
        return 2

if __name__=='__main__':
    raise SystemExit(main())
