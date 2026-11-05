"""
E5 / E6：pgvector 在 1 万 / 10 万块上的延迟、召回与索引体积。

合成数据：维度 1024（与 bge-m3 一致）。默认用「簇状」分布（200 个中心 + 噪声），
因为真实 embedding 有语义簇，各向同性高斯会让所有距离几乎相等、HNSW 召回失真；
`--clusters 1` 可复现那个最坏情况。向量归一化后用余弦距离。
查询集单独采样，ground truth 用 numpy 精确点积算出，再与 HNSW 结果比对。

用法：
    python tools/bench/pgvector_bench.py --n 10000
    python tools/bench/pgvector_bench.py --n 100000 --m 16 --ef 40
    python tools/bench/pgvector_bench.py --n 10000 --clusters 1   # 最坏情况对照

依赖：numpy、psycopg2-binary；容器由 README/实验记录里的 docker run 命令启动。
"""

import argparse
import struct
import time

import numpy as np
import psycopg2

DIM = 1024
PG = dict(host="127.0.0.1", port=5433, user="postgres", password="bench", dbname="bench")


def make_queries(vectors, count, seed):
    """查询要从数据分布里来：取随机样本加小扰动，模拟「问一个已有语义附近的问题」。"""
    rng = np.random.default_rng(seed)
    picked = vectors[rng.integers(0, len(vectors), count)]
    queries = picked + rng.normal(scale=0.05, size=picked.shape).astype(np.float32)
    queries /= np.linalg.norm(queries, axis=1, keepdims=True)
    return queries


def make_vectors(count, seed, clusters=200):
    rng = np.random.default_rng(seed)
    if clusters <= 1:
        vectors = rng.normal(size=(count, DIM)).astype(np.float32)
    else:
        centers = rng.normal(size=(clusters, DIM)).astype(np.float32) * 3.0
        assignment = rng.integers(0, clusters, count)
        vectors = centers[assignment] + rng.normal(size=(count, DIM)).astype(np.float32)
    vectors /= np.linalg.norm(vectors, axis=1, keepdims=True)
    return vectors


def binary_copy_rows(vectors):
    """pgvector 的 COPY BINARY 格式：int16 维度 + int16 未用位 + float32 分量，全部大端。"""
    header = b"PGCOPY\n\xff\r\n\x00" + struct.pack(">II", 0, 0)
    head = struct.pack(">hh", DIM, 0)
    yield header
    for vector in vectors:
        payload = head + vector.astype(">f4").tobytes()
        yield struct.pack(">h", 1) + struct.pack(">i", len(payload)) + payload
    yield struct.pack(">h", -1)


class CopyStream:
    """psycopg2 需要 file-like；把生成器包成按需读取，避免把 10 万行全拼进内存。"""

    def __init__(self, chunks):
        self._chunks = iter(chunks)
        self._buffer = bytearray()

    def read(self, size=-1):
        while size < 0 or len(self._buffer) < size:
            try:
                self._buffer.extend(next(self._chunks))
            except StopIteration:
                break
        if size < 0:
            out, self._buffer = bytes(self._buffer), bytearray()
            return out
        out, self._buffer = bytes(self._buffer[:size]), self._buffer[size:]
        return out


def load(conn, vectors, table):
    with conn.cursor() as cur:
        cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
        cur.execute(f"DROP TABLE IF EXISTS {table}")
        cur.execute(f"CREATE TABLE {table} (id bigserial PRIMARY KEY, emb vector({DIM}))")
    started = time.perf_counter()
    with conn.cursor() as cur:
        cur.copy_expert(
            f"COPY {table} (emb) FROM STDIN (FORMAT binary)",
            CopyStream(binary_copy_rows(vectors)),
        )
    conn.commit()
    return time.perf_counter() - started


def build_index(conn, table, m, ef_construction):
    with conn.cursor() as cur:
        cur.execute(f"DROP INDEX IF EXISTS {table}_hnsw")
        started = time.perf_counter()
        cur.execute(
            f"CREATE INDEX {table}_hnsw ON {table} "
            f"USING hnsw (emb vector_cosine_ops) WITH (m = {m}, ef_construction = {ef_construction})"
        )
    conn.commit()
    return time.perf_counter() - started


def ground_truth(vectors, queries, k):
    sims = queries @ vectors.T
    return np.argsort(-sims, axis=1)[:, :k] + 1  # id 从 1 开始


def query(conn, table, queries, k, ef_search, use_index):
    with conn.cursor() as cur:
        if use_index:
            cur.execute(f"SET hnsw.ef_search = {ef_search}")
            cur.execute("SET enable_seqscan = off")
        else:
            cur.execute("SET enable_indexscan = off")
            cur.execute("SET enable_bitmapscan = off")

        latencies = []
        hits = []
        for query_vector in queries:
            literal = "[" + ",".join(f"{value:.6f}" for value in query_vector) + "]"
            started = time.perf_counter()
            cur.execute(
                f"SELECT id FROM {table} ORDER BY emb <=> %s::vector LIMIT {k}", (literal,)
            )
            rows = [row[0] for row in cur.fetchall()]
            latencies.append((time.perf_counter() - started) * 1000)
            hits.append(rows)
    conn.rollback()
    return np.array(hits), np.array(latencies)


def recall(hits, truth):
    return float(np.mean([len(set(h) & set(t)) / len(t) for h, t in zip(hits, truth)]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=10_000)
    parser.add_argument("--queries", type=int, default=100)
    parser.add_argument("--k", type=int, default=10)
    parser.add_argument("--m", type=int, default=16)
    parser.add_argument("--ef", type=int, default=40)
    parser.add_argument("--ef-construction", type=int, default=64)
    parser.add_argument("--clusters", type=int, default=200)
    args = parser.parse_args()

    vectors = make_vectors(args.n, seed=1, clusters=args.clusters)
    queries = make_queries(vectors, args.queries, seed=2)
    truth = ground_truth(vectors, queries, args.k)

    conn = psycopg2.connect(**PG)
    table = f"items_{args.n}"
    insert_seconds = load(conn, vectors, table)

    with conn.cursor() as cur:
        cur.execute("SELECT pg_size_pretty(pg_total_relation_size(%s))", (table,))
        table_size = cur.fetchone()[0]

    build_seconds = build_index(conn, table, args.m, args.ef_construction)
    with conn.cursor() as cur:
        cur.execute("SELECT pg_size_pretty(pg_relation_size(%s))", (f"{table}_hnsw",))
        index_size = cur.fetchone()[0]

    scan_hits, scan_latency = query(conn, table, queries, args.k, args.ef, use_index=False)
    scan_recall = recall(scan_hits, truth)

    index_hits, index_latency = query(conn, table, queries, args.k, args.ef, use_index=True)
    index_recall = recall(index_hits, truth)

    print(f"\n=== pgvector N={args.n:,} dim={DIM} k={args.k} m={args.m} ef_search={args.ef} ===")
    print(f"| 指标 | 全表扫描 | HNSW |")
    print(f"|---|---:|---:|")
    print(f"| 平均延迟 | {np.mean(scan_latency):.1f} ms | {np.mean(index_latency):.1f} ms |")
    print(f"| P95 延迟 | {np.percentile(scan_latency, 95):.1f} ms | {np.percentile(index_latency, 95):.1f} ms |")
    print(f"| Recall@{args.k} | {scan_recall:.3f} | {index_recall:.3f} |")
    print(f"\n装表 {insert_seconds:.1f}s，建 HNSW {build_seconds:.1f}s，"
          f"表 {table_size}，索引 {index_size}")


if __name__ == "__main__":
    main()
