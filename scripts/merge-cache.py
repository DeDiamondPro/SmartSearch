# Script to merge SmartSearch's embedding cache files

import argparse
import sys

MAGIC_BYTES = b"OCSS"
FORMAT_VERSION = 1
EMBEDDING_SIZE = 384


def read_cache(file_path: str) -> dict[bytes, bytes]:
    with open(file_path, "rb") as f:
        # Verify header
        if f.read(4) != MAGIC_BYTES:
            raise ValueError(f"Invalid magic bytes in {file_path}")
        if int.from_bytes(f.read(1), "big") != FORMAT_VERSION:
            raise ValueError(f"Unsupported format version in {file_path}")

        # Read cache data
        cache: dict[bytes, bytes] = {}
        while True:
            # Read hash
            hash = f.read(16)
            if not hash:
                break
            if len(hash) != 16:
                raise ValueError(f"Invalid hash length in {file_path}")
            # Read embedding
            value = f.read(EMBEDDING_SIZE * 4)
            if len(value) != EMBEDDING_SIZE * 4:
                raise ValueError(f"Invalid embedding length in {file_path}")
            cache[hash] = value
        return cache


def write_cache(cache: dict[bytes, bytes], file_path: str):
    with open(file_path, "wb") as f:
        f.write(MAGIC_BYTES)
        f.write(FORMAT_VERSION.to_bytes(1, "big"))
        for hash, value in cache.items():
            f.write(hash)
            f.write(value)


def build_args():
    parser = argparse.ArgumentParser(
        description="Merge SmartSearch embedding cache files"
    )
    parser.add_argument("-i", "--input", help="An input file", action="extend", nargs="+", required=True)
    parser.add_argument("-o", "--output", help="The output file", required=True)

    return parser.parse_args()


if __name__ == "__main__":
    args = build_args()
    if len(args.input) < 2:
        print("At least two input files are required")
        sys.exit(1)

    merged: dict[bytes, bytes] = {}
    for file_path in args.input:
        cache = read_cache(file_path)
        for hash, embedding in cache.items():
            if hash in merged and embedding != merged[hash]:
                print(f"Warning: different embedding for same hash {hash.hex()} in {file_path}")
            merged[hash] = embedding
    write_cache(merged, args.output)
