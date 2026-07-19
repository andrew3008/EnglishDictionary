# IntelliJ IDEA Java import standard

Единый командный стандарт imports/code style для `platform-tracing` и связанных Java/Spring/Gradle модулей.

## Sources of truth

| Audience | Contract |
|----------|----------|
| IntelliJ IDEA | [`.editorconfig`](../../.editorconfig) + UI settings below |
| Codex / Cursor / other agents | [`.editorconfig`](../../.editorconfig) + [`AGENTS.md`](../../AGENTS.md) + [`.cursor/rules/java-imports.md`](../../.cursor/rules/java-imports.md) |
| Humans (IDEA setup) | this document |

`.editorconfig` — machine-readable import layout. **AGENTS.md** и **`.cursor/rules`** — явные инструкции для AI-агентов, которые не читают локальные UI-настройки IDEA.

## Goals

- minimize import churn after Cursor / LLM generation;
- avoid wildcard imports;
- keep imports deterministic across developers and agents;
- make `Ctrl+Alt+O` produce stable, reviewable diffs;
- prevent IDE-specific import layout noise.

## IntelliJ IDEA settings

### Code Style → Java → Imports

**Path:** Settings → Editor → Code Style → Java → Imports

| Setting | Value |
|---------|-------|
| Use single class import | **ON** |
| Class count to use import with `*` | **999** |
| Names count to use static import with `*` | **999** |
| Packages to Use Import with `*` | **empty** |
| Layout static imports separately | **ON** |

**Import layout** (сверху вниз):

1. all static imports
2. `java.*`
3. `javax.*`
4. `jakarta.*`
5. `org.*`
6. `com.*`
7. `space.*`
8. all other imports

Enable **Settings → Editor → Code Style → Enable EditorConfig support**.

> **Note:** В некоторых версиях IDEA группировка `jakarta.*` через `.editorconfig` может вести себя нестабильно. Если IDEA странно переставляет `jakarta`, временно держите `jakarta` в общем блоке `*` в `.editorconfig`.

### Auto Import → Java

**Path:** Settings → Editor → General → Auto Import → Java

| Setting | Value |
|---------|-------|
| Insert imports on paste | **Ask** |
| Add unambiguous imports on the fly | **ON** |
| Optimize imports on the fly | **OFF** |
| Show auto-import tooltip for classes | **ON** |
| Show auto-import tooltip for static methods and fields | **ON** |
| Include auto-import of static members in completion | **OFF** |

## Static imports

Static imports **разрешены** (explicit single-member only), в отдельном блоке сверху. Static wildcard imports **запрещены**.

## Human workflow (IDEA + Cursor)

После генерации Cursor/LLM:

1. compile/test по затронутому модулю;
2. **Ctrl+Alt+O** (Optimize Imports);
3. **Ctrl+Alt+I** (Reformat Code);
4. повторный compile/test.

Не нажимайте **Ctrl+Alt+O** в середине LLM-патча, когда код ещё частично сгенерирован.

## Agent workflow (Codex / Cursor)

См. [`AGENTS.md`](../../AGENTS.md). Кратко:

- не изобретать свой import order;
- explicit imports only, no wildcards;
- не создавать import-only churn в unrelated files;
- после правок — `compileJava` / `compileTestJava` для затронутого модуля.

## Verification

```powershell
git diff --stat
git diff --check
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
```

Wildcard scan (expect **0** matches):

```powershell
Select-String -Path (Get-ChildItem -Recurse -Filter *.java) `
  -Pattern "^\s*import\s+(static\s+)?[a-zA-Z0-9_.]+\.\*;"
```

## `.idea/codeStyles` vs `.editorconfig`

`.idea/` в `.gitignore`. Основной переносимый стандарт — **`.editorconfig`** + **AGENTS.md** + **`.cursor/rules`**.
