import concurrent.futures
import json
import statistics
import time
import urllib.request
import uuid

BASE_URL = "http://localhost:8080"
SCENARIOS = [5, 20, 50, 100]


def http_json(method: str, path: str, payload=None):
    url = f"{BASE_URL}{path}"
    data = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def worker_register_and_login(tag: str, idx: int) -> float:
    username = f"carga_{tag}_{idx}"
    t0 = time.perf_counter()
    http_json("POST", "/auth/register", {"username": username, "password": "senha123"})
    http_json("POST", "/auth/login", {"username": username, "password": "senha123"})
    return (time.perf_counter() - t0) * 1000.0


def run_scenario(users_count: int):
    tag = uuid.uuid4().hex[:6]
    t_start = time.perf_counter()
    latencies = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=users_count) as pool:
        futures = [pool.submit(worker_register_and_login, tag, i) for i in range(users_count)]
        for f in concurrent.futures.as_completed(futures):
            latencies.append(f.result())
    elapsed = max(0.001, time.perf_counter() - t_start)
    latencies.sort()
    p95_idx = min(len(latencies) - 1, max(0, int(len(latencies) * 0.95) - 1))
    return {
        "users": users_count,
        "min_ms": min(latencies),
        "avg_ms": statistics.mean(latencies),
        "p95_ms": latencies[p95_idx],
        "max_ms": max(latencies),
        "throughput_ops_s": (users_count * 2) / elapsed,
    }


def main():
    for n in SCENARIOS:
        r = run_scenario(n)
        print(
            f"usuarios={r['users']} min={r['min_ms']:.2f}ms med={r['avg_ms']:.2f}ms "
            f"p95={r['p95_ms']:.2f}ms max={r['max_ms']:.2f}ms vazao={r['throughput_ops_s']:.2f}op/s"
        )
    print(json.dumps(http_json("GET", "/metrics"), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
