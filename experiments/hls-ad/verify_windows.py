#!/usr/bin/env python3
"""Use decoded segment files produced by review_window.py."""
import argparse,json,pathlib,re,subprocess

def main():
    p=argparse.ArgumentParser();p.add_argument('snapshot',type=pathlib.Path);p.add_argument('review',type=pathlib.Path);p.add_argument('classpath');p.add_argument('start',type=int);p.add_argument('end',type=int);a=p.parse_args()
    lines=a.snapshot.with_name('playlist.m3u8').read_text().splitlines();header=[];blocks=[];pending=[];key=None
    for line in lines:
        if line.startswith('#EXT-X-KEY:'):key=line
        elif line=='#EXT-X-DISCONTINUITY' or line.startswith('#EXTINF:'):pending.append(line)
        elif line and not line.startswith('#'):blocks.append((key,pending+[line]));pending=[]
        elif not blocks and not pending and line!='#EXT-X-ENDLIST':header.append(line)
    out=header[:];previous=None;cache={}
    for i in range(a.start,a.end+1):
        key,block=blocks[i]
        if key!=previous:
            if key:out.append(key)
            previous=key
        out+=block;cache[block[-1]]=a.review/f'segment-{i}.ts'
    out.append('#EXT-X-ENDLIST');original=a.review/'original-private.m3u8';filtered=a.review/'filtered-private.m3u8';original.write_text('\n'.join(out)+'\n')
    base=json.loads(a.snapshot.read_text())['base']
    result=subprocess.run(['java','-cp',a.classpath,'FilterPlaylist',base,str(original),str(filtered)],capture_output=True,text=True,check=True)
    assert result.stdout.strip()!='unchanged','Expected reviewed complete group to match'
    localized=[]
    for line in filtered.read_text().splitlines():
        if line.startswith('#EXT-X-KEY:'):
            # Explicitly decoded cache; encrypted CDN filter is verified separately.
            if line!='#EXT-X-KEY:METHOD=NONE':raise ValueError('Use dedicated encrypted-original verification')
        elif line and not line.startswith('#'):line=str(cache[line])
        localized.append(line)
    local=a.review/'filtered-local-private.m3u8';local.write_text('\n'.join(localized)+'\n')
    r=subprocess.run(['ffmpeg','-v','warning','-protocol_whitelist','file,crypto,data','-allowed_extensions','ALL','-i',str(local),'-f','null','-'],capture_output=True,text=True,timeout=45);(a.review/'filtered-decode.log').write_text(r.stderr)
    summary={'removed':result.stdout.strip(),'decode_exit':r.returncode,'retained_segments':sum(bool(l) and not l.startswith('#') for l in localized),'warning_lines':len(r.stderr.splitlines())};(a.review/'filtered-result.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(summary)
if __name__=='__main__':main()
