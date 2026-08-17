#!/usr/bin/env python3
"""Slice 0.1 inventory generator + Slice 0.2 transfer (not copy) of SHARED types."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OTEL = ROOT / "platform-starters-tracing" / "spring-boot-starter-platform-tracing-otel"
POLICY = ROOT / "platform-starters-tracing" / "platform-tracing-runtime-policy"
API = ROOT / "platform-starters-tracing" / "spring-boot-starter-platform-tracing-api"
UTIL = ROOT / "platform-starters-common" / "platform-starters-common-util"
EXT = ROOT / "platform-starters-tracing" / "platform-tracing-otel-javaagent-extension"
DOCS = Path(__file__).resolve().parent

REPLACEMENTS = [
    (
        "space.br1440.platform.tracing.otel.runtime.versioned",
        "space.br1440.platform.tracing.runtime.versioned",
    ),
    (
        "space.br1440.platform.tracing.otel.sampling",
        "space.br1440.platform.tracing.runtime.sampling",
    ),
    (
        "space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceContext",
        "space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceContext",
    ),
    (
        "space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceMirror",
        "space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceMirror",
    ),
    (
        "space.br1440.platform.tracing.otel.propagation.control",
        "space.br1440.platform.tracing.runtime.propagation.control",
    ),
    (
        "space.br1440.platform.tracing.otel.propagation.RequestIdSupport",
        "space.br1440.platform.tracing.runtime.propagation.RequestIdSupport",
    ),
]


def read_text(path: Path) -> str:
    return path.read_bytes().decode("utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not text.endswith("\n"):
        text += "\n"
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))


def rewrite(text: str) -> str:
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    return text


def class_name(path: Path) -> str:
    rel = path.as_posix()
    marker = "/java/"
    idx = rel.rfind(marker)
    fqcn = rel[idx + len(marker) : -5].replace("/", ".")
    return fqcn


def list_java(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*.java") if p.is_file())


def transfer(src: Path, dst: Path) -> None:
    text = rewrite(read_text(src))
    write_text(dst, text)
    src.unlink()


def move_tree(src_rel: str, dst_rel: str, source_set: str) -> None:
    src_root = OTEL / "src" / source_set / "java" / src_rel
    dst_root = POLICY / "src" / source_set / "java" / dst_rel
    if not src_root.exists():
        return
    for src in list_java(src_root):
        rel = src.relative_to(src_root)
        transfer(src, dst_root / rel)
    # remove empty dirs later


def move_named(src_rel: str, dst_rel: str, source_set: str) -> None:
    src = OTEL / "src" / source_set / "java" / src_rel
    dst = POLICY / "src" / source_set / "java" / dst_rel
    if src.exists():
        transfer(src, dst)


def prune_empty(root: Path) -> None:
    if not root.exists():
        return
    for directory in sorted((p for p in root.rglob("*") if p.is_dir()), reverse=True):
        try:
            directory.rmdir()
        except OSError:
            pass


def rewrite_tree(root: Path) -> int:
    changed = 0
    for path in list_java(root):
        original = read_text(path)
        updated = rewrite(original)
        if updated != original:
            write_text(path, updated)
            changed += 1
    return changed


def generate_inventory() -> None:
    records = []

    def add(path: Path, origin: str, classification: str, placement: str, consumer: str, why: str) -> None:
        records.append(
            {
                "class": class_name(path),
                "originModule": origin,
                "whyNeeded": why,
                "consumer": consumer,
                "classification": classification,
                "modulePlacement": placement,
            }
        )

    for path in list_java(API / "src" / "main" / "java"):
        add(
            path,
            "spring-boot-starter-platform-tracing-api",
            "SHARED_AGENT_APPLICATION",
            "KEEP:spring-boot-starter-platform-tracing-api",
            "BOTH",
            "published API contract; unused types are still legitimate API surface, not Agent runtime",
        )
    for path in list_java(UTIL / "src" / "main" / "java"):
        name = path.stem
        needed = name in {"StringUtils", "MapUtils", "ArrayUtils"}
        add(
            path,
            "platform-starters-common-util",
            "SHARED_AGENT_APPLICATION" if needed else "ACCIDENTAL",
            "KEEP:platform-starters-common-util",
            "BOTH" if needed else "APPLICATION",
            "JDK-only utility already in a dedicated module",
        )

    move_prefixes = (
        ".sampling.",
        ".runtime.versioned.",
        ".mdc.remote.RemoteServiceTraceContext",
        ".mdc.remote.RemoteServiceTraceMirror",
        ".propagation.control.",
        ".propagation.RequestIdSupport",
    )
    for path in list_java(OTEL / "src" / "main" / "java"):
        fqcn = class_name(path)
        if any(token in fqcn for token in move_prefixes):
            add(
                path,
                "spring-boot-starter-platform-tracing-otel",
                "SHARED_AGENT_APPLICATION",
                "MOVE:platform-tracing-runtime-policy",
                "BOTH",
                "logging-agnostic domain/runtime primitive required by Agent and application",
            )
        else:
            add(
                path,
                "spring-boot-starter-platform-tracing-otel",
                "APPLICATION_ONLY",
                "KEEP:spring-boot-starter-platform-tracing-otel",
                "APPLICATION",
                "application-plane implementation; present in current hybrid JAR only because of whole-module embedding",
            )

    payload = {
        "schemaVersion": 1,
        "generatedAt": "2026-08-16",
        "commit": "5ac5d5a82cc021ff1bc0777f2e8b0c0c4d28750e",
        "embeddedClassCount": len(records),
        "agentRuntimeModule": "NOT_CREATED",
        "runtimePolicyModule": "platform-tracing-runtime-policy",
        "hardAssertions": {
            "approvedOtelClosureSlf4j": 0,
            "approvedOtelClosureMdc": 0,
            "approvedOtelClosureSpring": 0,
        },
        "classes": records,
    }
    write_text(
        DOCS / "agent-runtime-class-closure.json",
        json.dumps(payload, indent=2, ensure_ascii=False),
    )


def main() -> None:
    generate_inventory()

    move_tree(
        "space/br1440/platform/tracing/otel/sampling",
        "space/br1440/platform/tracing/runtime/sampling",
        "main",
    )
    move_tree(
        "space/br1440/platform/tracing/otel/sampling",
        "space/br1440/platform/tracing/runtime/sampling",
        "test",
    )
    move_tree(
        "space/br1440/platform/tracing/otel/runtime/versioned",
        "space/br1440/platform/tracing/runtime/versioned",
        "main",
    )
    move_tree(
        "space/br1440/platform/tracing/otel/runtime/versioned",
        "space/br1440/platform/tracing/runtime/versioned",
        "test",
    )
    move_tree(
        "space/br1440/platform/tracing/otel/propagation/control",
        "space/br1440/platform/tracing/runtime/propagation/control",
        "main",
    )
    move_tree(
        "space/br1440/platform/tracing/otel/propagation/control",
        "space/br1440/platform/tracing/runtime/propagation/control",
        "test",
    )
    move_named(
        "space/br1440/platform/tracing/otel/propagation/RequestIdSupport.java",
        "space/br1440/platform/tracing/runtime/propagation/RequestIdSupport.java",
        "main",
    )
    move_named(
        "space/br1440/platform/tracing/otel/propagation/RequestIdSupportTest.java",
        "space/br1440/platform/tracing/runtime/propagation/RequestIdSupportTest.java",
        "test",
    )
    move_named(
        "space/br1440/platform/tracing/otel/mdc/remote/RemoteServiceTraceContext.java",
        "space/br1440/platform/tracing/runtime/remote/RemoteServiceTraceContext.java",
        "main",
    )
    move_named(
        "space/br1440/platform/tracing/otel/mdc/remote/RemoteServiceTraceMirror.java",
        "space/br1440/platform/tracing/runtime/remote/RemoteServiceTraceMirror.java",
        "main",
    )
    move_named(
        "space/br1440/platform/tracing/otel/mdc/remote/RemoteServiceTraceContextTest.java",
        "space/br1440/platform/tracing/runtime/remote/RemoteServiceTraceContextTest.java",
        "test",
    )
    move_named(
        "space/br1440/platform/tracing/otel/mdc/remote/RemoteServiceTraceMirrorTest.java",
        "space/br1440/platform/tracing/runtime/remote/RemoteServiceTraceMirrorTest.java",
        "test",
    )

    prune_empty(OTEL / "src")

    rewrite_tree(ROOT / "platform-starters-tracing")
    print("transfer complete")


if __name__ == "__main__":
    main()
