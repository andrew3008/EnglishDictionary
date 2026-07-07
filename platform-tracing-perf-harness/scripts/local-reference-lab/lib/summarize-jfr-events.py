#!/usr/bin/env python3
"""Extract compact JFR summary fields from jfr CLI outputs. PR-9H-E2L-JFR-summary/finalize."""
from __future__ import annotations

import json
import re
import statistics
import sys
from typing import Any


def parse_summary_text(text: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or ":" not in line:
            continue
        key, _, val = line.partition(":")
        key = key.strip()
        val = val.strip()
        if key == "Version":
            # JFR file format version (e.g. 2.1), not JDK — skip unless no JVM line found.
            out.setdefault("jfrFormatVersion", val)
        elif key in ("JVM Version", "JDK Version"):
            out["jdkVersion"] = val
        elif key == "JVM Arguments":
            out["commandLine"] = val
        elif key == "Recording":
            out["recordingName"] = val
        elif key == "Start":
            out["startTime"] = val
        elif key == "Duration":
            m = re.search(r"([\d.]+)\s*s", val, re.I)
            if m:
                out["durationSeconds"] = float(m.group(1))
        elif key == "Host":
            out["hostName"] = val
        elif key == "OS":
            out["osDescription"] = val
    return out


def duration_to_ms(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value) / 1_000_000.0
    if isinstance(value, str):
        m = re.fullmatch(r"PT([\d.]+)S", value.strip())
        if m:
            return float(m.group(1)) * 1000.0
    return None


def load_events(path: str) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read().strip()
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        parsed = None
    if isinstance(parsed, dict):
        rec = parsed.get("recording")
        if isinstance(rec, dict) and isinstance(rec.get("events"), list):
            return [e for e in rec["events"] if isinstance(e, dict)]
        if isinstance(parsed.get("events"), list):
            return [e for e in parsed["events"] if isinstance(e, dict)]
    if isinstance(parsed, list):
        return [e for e in parsed if isinstance(e, dict)]
    events: list[dict[str, Any]] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            events.append(obj)
    return events


def event_type(event: dict[str, Any]) -> str:
    return str(event.get("type") or event.get("eventType") or "")


def event_values(event: dict[str, Any]) -> dict[str, Any]:
    vals = event.get("values")
    return vals if isinstance(vals, dict) else event


def summarize_gc(events: list[dict[str, Any]]) -> dict[str, Any]:
    pauses_ms: list[float] = []
    gc_names: set[str] = set()
    heap_before: list[int] = []
    heap_after: list[int] = []
    heap_committed: list[int] = []
    heap_max: list[int] = []
    present = {
        "jdk.GarbageCollection": False,
        "jdk.GCPhasePause": False,
        "jdk.GCHeapSummary": False,
        "jdk.YoungGarbageCollection": False,
    }

    for ev in events:
        et = event_type(ev)
        vals = event_values(ev)
        if et == "jdk.GarbageCollection":
            present["jdk.GarbageCollection"] = True
            name = vals.get("name")
            if name:
                gc_names.add(str(name))
            for field in ("duration", "sumOfPauses", "longestPause"):
                ms = duration_to_ms(vals.get(field))
                if ms is not None and ms >= 0:
                    pauses_ms.append(ms)
                    break
        elif et == "jdk.GCPhasePause":
            present["jdk.GCPhasePause"] = True
            ms = duration_to_ms(vals.get("duration"))
            if ms is not None and ms >= 0:
                pauses_ms.append(ms)
            name = vals.get("name")
            if name:
                gc_names.add(str(name))
        elif et == "jdk.YoungGarbageCollection":
            present["jdk.YoungGarbageCollection"] = True
            ms = duration_to_ms(vals.get("duration"))
            if ms is not None and ms >= 0:
                pauses_ms.append(ms)
        elif et == "jdk.GCHeapSummary":
            present["jdk.GCHeapSummary"] = True
            for src, dest in (
                ("heapUsed", heap_before),
                ("heapUsedAfterGc", heap_after),
                ("heapCommitted", heap_committed),
                ("heapMax", heap_max),
            ):
                v = vals.get(src)
                if isinstance(v, (int, float)) and v >= 0:
                    dest.append(int(v))

    summary: dict[str, Any] = {
        "eventPresent": any(present.values()),
        "eventsChecked": present,
    }
    if pauses_ms:
        pauses_ms.sort()
        summary.update(
            {
                "gcPauseCount": len(pauses_ms),
                "gcPauseTotalMillis": round(sum(pauses_ms), 3),
                "gcPauseMaxMillis": round(max(pauses_ms), 3),
                "gcPauseAvgMillis": round(statistics.mean(pauses_ms), 3),
            }
        )
        if len(pauses_ms) >= 20:
            idx = max(0, int(len(pauses_ms) * 0.95) - 1)
            summary["gcPauseP95Millis"] = round(pauses_ms[idx], 3)
    if gc_names:
        summary["gcNames"] = sorted(gc_names)
    if heap_before:
        summary["heapUsedBeforeGcBytes"] = max(heap_before)
    if heap_after:
        summary["heapUsedAfterGcBytes"] = max(heap_after)
    if heap_committed:
        summary["heapCommittedBytes"] = max(heap_committed)
    if heap_max:
        summary["heapMaxBytes"] = max(heap_max)
    return summary


def summarize_allocation(events: list[dict[str, Any]]) -> dict[str, Any]:
    alloc_events = 0
    total_bytes = 0
    types = (
        "jdk.ObjectAllocationInNewTLAB",
        "jdk.ObjectAllocationOutsideTLAB",
        "jdk.ObjectAllocationSample",
    )
    present = {t: False for t in types}
    for ev in events:
        et = event_type(ev)
        if et not in types:
            continue
        present[et] = True
        vals = event_values(ev)
        sz = vals.get("allocationSize") or vals.get("weight") or vals.get("size")
        if isinstance(sz, (int, float)) and sz > 0:
            alloc_events += 1
            total_bytes += int(sz)
    out: dict[str, Any] = {
        "allocationEventsPresent": any(present.values()),
        "eventsChecked": present,
    }
    if alloc_events > 0:
        out["allocationSummaryAvailable"] = True
        out["allocationEventCount"] = alloc_events
        out["allocationTotalBytes"] = total_bytes
    else:
        out["allocationSummaryAvailable"] = False
    return out


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: summarize-jfr-events.py <summary.txt> <events.json>", file=sys.stderr)
        return 2
    summary_path, events_path = sys.argv[1], sys.argv[2]
    with open(summary_path, encoding="utf-8", errors="replace") as fh:
        meta = parse_summary_text(fh.read())
    events = load_events(events_path)
    payload = {
        "recordingMetadata": meta,
        "gcSummary": summarize_gc(events),
        "allocationSummary": summarize_allocation(events),
    }
    json.dump(payload, sys.stdout, indent=2, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
