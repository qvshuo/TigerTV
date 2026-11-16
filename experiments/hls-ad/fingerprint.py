#!/usr/bin/env python3
"""Offline experiment: compare two short audio files; never classifies ads by itself.
Requires ffmpeg and numpy. Inputs must be local files. Outputs no URLs.
"""
import argparse
import json
import resource
import subprocess
import time
import numpy as np


def fingerprint(path, audio_filter):
    cmd = ['ffmpeg', '-v', 'error', '-i', path, '-vn', '-t', '60', '-ac', '1', '-ar', '4000']
    if audio_filter:
        cmd += ['-af', audio_filter]
    cmd += ['-f', 's16le', '-']
    data = subprocess.run(cmd, check=True, capture_output=True).stdout
    audio = np.frombuffer(data, dtype='<i2').astype(np.float32)
    n = len(audio) // 1000
    if n < 20:
        raise ValueError('less than five seconds of decoded audio')
    chunks = audio[:n * 1000].reshape(n, 1000)
    spectrum = np.abs(np.fft.rfft(chunks * np.hanning(1000), axis=1))
    edges = np.geomspace(10, 490, 25).astype(int)
    features = np.stack([np.log1p(np.mean(spectrum[:, edges[i]:edges[i + 1]], axis=1)) for i in range(24)], axis=1)
    features -= features.mean(axis=1, keepdims=True)
    features /= np.maximum(np.linalg.norm(features, axis=1, keepdims=True), 1e-9)
    return features


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('reference')
    parser.add_argument('candidate')
    parser.add_argument('--filter', default='')
    args = parser.parse_args()
    started = time.monotonic()
    a, b = fingerprint(args.reference, ''), fingerprint(args.candidate, args.filter)
    scores = []
    for shift in range(-40, 41):
        i, j = max(0, -shift), max(0, shift)
        length = min(len(a) - i, len(b) - j)
        if length >= min(len(a), len(b)) * .75:
            scores.append((float(np.mean(np.sum(a[i:i + length] * b[j:j + length], axis=1))), shift, length))
    score, shift, length = max(scores)
    print(json.dumps({'score': round(score, 5), 'offset_seconds': shift / 4, 'matched_seconds': length / 4, 'elapsed_seconds': round(time.monotonic() - started, 3), 'max_rss_bytes_macos': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}))


if __name__ == '__main__':
    main()
