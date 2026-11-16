#!/usr/bin/env python3
"""Bounded offline HLS audit. Business search/detail are delegated to TigerTV's Kotlin CLI.
Private output contains upstream URLs; publish only summary.json. No automatic ad classification.
"""
import argparse, concurrent.futures, hashlib, ipaddress, json, pathlib, re, socket, subprocess, time
import urllib.parse, urllib.request
from collections import Counter

UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15'

def check_url(url):
    u = urllib.parse.urlsplit(url)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username: raise ValueError('invalid HTTP URL')
    for item in socket.getaddrinfo(u.hostname, u.port or (443 if u.scheme == 'https' else 80), type=socket.SOCK_STREAM):
        ip = ipaddress.ip_address(item[4][0])
        if not ip.is_global and ip not in ipaddress.ip_network('198.18.0.0/15'): raise ValueError('non-public address')

class Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def get(url, cap=10 * 1024 * 1024, partial=False):
    url = urllib.parse.quote(url, safe=":/?&=%+#@,;[]!$()*")
    check_url(url); started = time.monotonic()
    headers = {'User-Agent': UA}
    if partial: headers['Range'] = f'bytes=0-{cap-1}'
    opener = urllib.request.build_opener(Redirects())
    with opener.open(urllib.request.Request(url, headers=headers), timeout=5) as response:
        data = bytearray()
        while len(data) < cap + (not partial):
            if time.monotonic() - started > 15: raise TimeoutError('request body budget exceeded')
            chunk = response.read(min(65536, cap + (not partial) - len(data)))
            if not chunk: break
            data.extend(chunk)
        if not partial and len(data) > cap: raise ValueError('response cap exceeded')
        return bytes(data), response.geturl(), round(time.monotonic() - started, 3)

def attributes(line):
    return dict((m.group(1), m.group(2).strip('"')) for m in re.finditer(r'([A-Z0-9-]+)=("[^"]*"|[^,]+)', line.split(':', 1)[-1]))

def playlist(url, folder):
    chain = []; variants_seen = set()
    for depth in range(5):
        if url in variants_seen: raise ValueError('playlist cycle')
        variants_seen.add(url)
        body, base, seconds = get(url); chain.append({'seconds': seconds, 'bytes': len(body)})
        if not body.lstrip().startswith(b'#EXTM3U'): return {'format': 'non_hls', 'requests': chain}
        text = body.decode('utf-8-sig'); lines = [x.strip() for x in text.splitlines() if x.strip()]
        variants = [(attributes(x), urllib.parse.urljoin(base, lines[i+1])) for i,x in enumerate(lines[:-1]) if x.startswith('#EXT-X-STREAM-INF:')]
        if variants:
            variants.sort(key=lambda v: int(v[0].get('BANDWIDTH', 0))); selected = variants[len(variants)//2]
            url = selected[1]; chain[-1]['selection'] = selected[0]; continue
        folder.mkdir(parents=True, exist_ok=True); (folder/'playlist.m3u8').write_text(text)
        segments=[]; tags=Counter(); key=None; init=None; byte_range=None; boundary=False; duration=None; total=0.0
        for line in lines:
            if line.startswith('#'): tags[line.split(':',1)[0]] += 1
            if line.startswith('#EXT-X-KEY:'): key=attributes(line)
            elif line.startswith('#EXT-X-MAP:'): init=attributes(line)
            elif line.startswith('#EXT-X-BYTERANGE:'): byte_range=line.split(':',1)[1]
            elif line=='#EXT-X-DISCONTINUITY': boundary=True
            elif line.startswith('#EXTINF:'): duration=float(line.split(':',1)[1].split(',')[0])
            elif not line.startswith('#') and duration is not None:
                uri=urllib.parse.urljoin(base,line); u=urllib.parse.urlsplit(uri)
                segments.append({'index':len(segments),'start':round(total,6),'duration':duration,'url':uri,'key':key,'map':init,'byte_range':byte_range,'discontinuity':boundary,'directory':u.hostname+u.path.rsplit('/',1)[0]})
                total+=duration;duration=None;byte_range=None;boundary=False
        private={'base':base,'segments':segments}; (folder/'media-private.json').write_text(json.dumps(private,ensure_ascii=False,indent=2))
        groups=Counter(s['directory'] for s in segments)
        result={'format':'hls','requests':chain,'segments':len(segments),'duration':round(total,6),'tags':dict(tags),'directory_sizes':sorted(groups.values()),'key_methods':dict(Counter((s['key'] or {}).get('METHOD','NONE') for s in segments))}
        if segments:
            try:
                data,_,elapsed=get(segments[0]['url'],262144,True)
                result['first_segment_sample']={'bytes':len(data),'seconds':elapsed,'sha256':hashlib.sha256(data).hexdigest()}
            except Exception as error:
                result['first_segment_sample']={'error': type(error).__name__}
        return result
    raise ValueError('playlist depth exceeded')

def audit(site, args):
    index=site['index']; folder=args.output/f'site-{index}';folder.mkdir(parents=True,exist_ok=True)
    result={'site':site['name'],'samples':[]}
    for title in args.titles:
        sample={'query':title,'lines':[]};result['samples'].append(sample)
        try:
            check_url(site['api']); started=time.monotonic()
            def cli(*params):
                p=subprocess.run([str(args.cli),'--config',str(args.input/f'site-{index}.json'),*params],capture_output=True,text=True,timeout=65)
                if p.returncode:
                    (folder/(hashlib.sha256((title+params[0]).encode()).hexdigest()[:12]+'-error-private.txt')).write_text(p.stderr)
                    raise RuntimeError('Kotlin CLI request failed; see private log')
                return json.loads(p.stdout)
            found=cli('search',title); sample['search_seconds']=round(time.monotonic()-started,3)
            sources=[s for w in found['works'] for s in w['sources'] if s['site']==site['name']]
            if not sources: sample['status']='no_match';continue
            selected=next((s for s in sources if s['vod_name']==title),sources[0]);started=time.monotonic()
            detail=cli('fetch','--site',site['name'],'--video-id',str(selected['vod_id']));sample['detail_seconds']=round(time.monotonic()-started,3)
            (folder/(hashlib.sha256(title.encode()).hexdigest()[:8]+'-detail-private.json')).write_text(json.dumps(detail,ensure_ascii=False))
            for line_index,line in enumerate(detail['play_sources'][:args.max_lines]):
                for episode_index,episode in enumerate(line['episodes'][:args.episodes]):
                    entry={'line':line_index,'episode':episode_index,'label':episode['name'],'episode_count':len(line['episodes'])};sample['lines'].append(entry)
                    dest=folder/f'{hashlib.sha256(title.encode()).hexdigest()[:8]}-{line_index}-{episode_index}'
                    try: entry.update(playlist(episode['url'],dest));entry['status']='ok'
                    except Exception as e: entry['status']='error';entry['reason']=type(e).__name__
            sample['status']='ok'
        except Exception as e: sample['status']='error';sample['reason']=type(e).__name__
    (folder/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--input',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--cli',type=pathlib.Path,required=True);p.add_argument('--titles',nargs='+',default=['嫁金钗']);p.add_argument('--episodes',type=int,default=1);p.add_argument('--max-lines',type=int,default=3);p.add_argument('--sites',nargs='+',required=True);args=p.parse_args()
    args.cli=args.cli.resolve();args.output.mkdir(parents=True,exist_ok=True)
    sites=json.loads((args.input/'sites-private.json').read_text())
    if args.sites:sites=[s for s in sites if s['name'] in args.sites]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:results=list(ex.map(lambda s:audit(s,args),sites))
    (args.output/'summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    print('sources',len(results),'hls',sum(l.get('format')=='hls' for s in results for t in s['samples'] for l in t['lines']))
if __name__=='__main__':main()
