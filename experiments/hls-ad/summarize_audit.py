#!/usr/bin/env python3
"""Compile FilterPlaylist.java against Core and the Kotlin stdlib before running."""
import argparse,json,pathlib,re,statistics,subprocess

def main():
    p=argparse.ArgumentParser();p.add_argument('root',type=pathlib.Path);p.add_argument('classpath');p.add_argument('output',type=pathlib.Path);a=p.parse_args();rows=[]
    median=lambda v:round(statistics.median(v),3) if v else None
    quality={d['source_index']:d for d in json.loads((a.root/'quality/summary.json').read_text())}
    for folder in sorted((a.root/'top10').glob('site-*')):
        datasets=[json.loads((folder/'summary.json').read_text())];retry=a.root/'top10-retry'/folder.name
        if (retry/'summary.json').exists():datasets.append(json.loads((retry/'summary.json').read_text()))
        site=datasets[0]['site'];samples=[s for d in datasets for s in d['samples']];lines=[l for s in samples for l in s['lines']];hls=[l for l in lines if l.get('format')=='hls'];media_samples=[l['first_segment_sample'] for l in hls if l.get('first_segment_sample')]
        # Select latest captured response per title/line/episode, never count a retry as a new episode.
        captures={}
        for d in [folder,retry]:
            for f in d.glob('*/playlist.m3u8'):captures[f.parent.name]=f
        results=[]
        for name,f in sorted(captures.items()):
            target=f.with_name('filtered.m3u8');r=subprocess.run(['java','-cp',a.classpath,'FilterPlaylist',site,str(f),str(target)],capture_output=True,text=True,check=True)
            intervals=[{'start_ms':int(s),'end_ms':int(e)} for s,e in re.findall(r'startMs=(\d+), endMs=(\d+)',r.stdout)]
            before=f.read_text().splitlines();after=target.read_text().splitlines();uris=lambda ls:[l for l in ls if l and not l.startswith('#')]
            old=uris(before);new=uris(after);kept=set(new);assert [u for u in old if u in kept]==new,'Kept URI order changed'
            results.append({'capture':name,'original_segments':len(old),'kept_segments':len(new),'removed_segments':len(old)-len(new),'removed_intervals':intervals,'removed_ms':sum(i['end_ms']-i['start_ms'] for i in intervals)})
        rows.append({'site':site,'api_search_ok':sum('detail_seconds' in s for s in samples),'api_attempts':len(samples),'search_median_s':median([s['search_seconds'] for s in samples if 'search_seconds' in s]),'detail_median_s':median([s['detail_seconds'] for s in samples if 'detail_seconds' in s]),'hls_responses':len(hls),'line_requests':len(lines),'hls_fetch_median_s':median([sum(r['seconds'] for r in l['requests']) for l in hls]),'partial_sample_median_KiB_s':median([s['bytes']/1024/s['seconds'] for s in media_samples]),'quality_sample':quality.get(int(folder.name.split('-')[1])),'captures':results,'removed_groups':sum(len(r['removed_intervals']) for r in results),'removed_ms':sum(r['removed_ms'] for r in results)})
    a.output.write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    for row in rows:print(row['site'],row['hls_responses'],row['removed_groups'],row['removed_ms'])
if __name__=='__main__':main()
