#!/usr/bin/env python3
"""Offline review only: bounded public HLS segments, identity AES-128 with explicit IV.
Never emits key material. Refuses DRM, byte ranges and initialization maps.
"""
import argparse,json,pathlib,subprocess,urllib.parse
from source_audit import get

def main():
    p=argparse.ArgumentParser();p.add_argument('snapshot',type=pathlib.Path);p.add_argument('--start',type=int,required=True);p.add_argument('--end',type=int,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
    x=json.loads(a.snapshot.read_text());segments=x['segments'];a.output.mkdir(parents=True,exist_ok=True);total=0;keys={};records=[]
    with (a.output/'window.ts').open('wb') as joined:
        for i in range(a.start,a.end+1):
            z=segments[i]
            if z['byte_range'] or z['map']:raise ValueError('map/byte-range not supported in offline review')
            b,_,seconds=get(z['url'],8*1024*1024);total+=len(b)
            if total>48*1024*1024:raise ValueError('review window exceeds 48MiB')
            key=z['key'] or {};method=key.get('METHOD','NONE');data=b
            if method!='NONE':
                if method!='AES-128' or key.get('KEYFORMAT','identity')!='identity' or 'IV' not in key:raise ValueError('encrypted format not supported')
                url=urllib.parse.urljoin(x['base'],key['URI'])
                if url not in keys:
                    raw,_,_=get(url,1024)
                    if len(raw)!=16:raise ValueError('invalid AES key size')
                    keys[url]=raw
                iv=key['IV'].removeprefix('0x').removeprefix('0X')
                if len(iv)!=32:raise ValueError('invalid explicit IV')
                r=subprocess.run(['openssl','enc','-d','-aes-128-cbc','-K',keys[url].hex(),'-iv',iv],input=b,capture_output=True,timeout=10)
                if r.returncode:raise ValueError('AES decode failed')
                data=r.stdout
            (a.output/f"segment-{i}.ts").write_bytes(data)
            joined.write(data)
            records.append({'index':i,'start':z['start'],'duration':z['duration'],'download_bytes':len(b),'seconds':seconds,'method':method})
    (a.output/'segments.json').write_text(json.dumps(records,indent=2));(a.output/'identity-private.json').write_text(json.dumps({'snapshot':str(a.snapshot),'start':a.start,'end':a.end,'bytes':total}))
    r=subprocess.run(['ffmpeg','-v','warning','-y','-i',str(a.output/'window.ts'),'-vn','-ac','1','-ar','16000',str(a.output/'window.wav')],capture_output=True,text=True,timeout=40)
    (a.output/'decode.log').write_text(r.stderr)
    print('segments',len(records),'bytes',total,'decode_exit',r.returncode)
if __name__=='__main__':main()
