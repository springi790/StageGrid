#!/usr/bin/env python3
"""Generate 16 sparse PCM16 WAV synchronization-test stems.

Each file has one-sample impulses at 0, 30, 60, 120 and 300 seconds.
No third-party packages are required.
"""
from __future__ import annotations
import os
import struct
import sys
from pathlib import Path

SAMPLE_RATE = 48_000
CHANNELS = 1
BITS = 16
DURATION_SECONDS = 301
IMPULSES = (0, 30, 60, 120, 300)
TRACKS = 16


def header(data_size: int) -> bytes:
    block_align = CHANNELS * BITS // 8
    byte_rate = SAMPLE_RATE * block_align
    return b"".join([
        b"RIFF", struct.pack("<I", 36 + data_size), b"WAVE",
        b"fmt ", struct.pack("<IHHIIHH", 16, 1, CHANNELS, SAMPLE_RATE, byte_rate, block_align, BITS),
        b"data", struct.pack("<I", data_size),
    ])


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} OUTPUT_DIR", file=sys.stderr)
        return 2
    root = Path(sys.argv[1])
    root.mkdir(parents=True, exist_ok=True)
    bytes_per_frame = CHANNELS * BITS // 8
    frames = DURATION_SECONDS * SAMPLE_RATE
    data_size = frames * bytes_per_frame
    if data_size > 0xFFFFFFFF - 36:
        raise SystemExit("fixture exceeds classic RIFF size")

    for index in range(TRACKS):
        path = root / f"Sync {index + 1:02d}.wav"
        with path.open("wb") as f:
            f.write(header(data_size))
            # Seek to the final byte first; on sparse-capable filesystems holes use little physical disk.
            f.seek(44 + data_size - 1)
            f.write(b"\0")
            amplitude = 28000 - index * 250
            for second in IMPULSES:
                f.seek(44 + second * SAMPLE_RATE * bytes_per_frame)
                f.write(struct.pack("<h", amplitude))
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
