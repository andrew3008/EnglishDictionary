---
description: Java import policy for Cursor/Codex generated changes
globs:
  - "**/*.java"
alwaysApply: true
---

# Java import policy

Use the repository [`.editorconfig`](../../.editorconfig) as the source of truth.

Rules:

1. Use explicit single-class imports only.
2. Never use wildcard imports.
3. Never use static wildcard imports.
4. Keep static imports in a separate block.
5. Do not reorder imports in unrelated files.
6. Do not create import-only diffs unless the file is already being edited.
7. Do not rely on generated imports being accepted as-is; ensure compile-clean imports.
8. For tests, explicit static imports for AssertJ/Mockito/JUnit are allowed.
9. For platform code, avoid ambiguous imports:
   - `Context`
   - `Span`
   - `Scope`
   - `Attributes`
   - `Date`
   - `Duration`
   - `Configuration`
   - `Status`
   - `TraceContext`
   - `ValidationResult`
   - `ControlResult`
   - `RuntimePolicy`

Expected order:

```java
import static ...

import java...
import javax...
import jakarta...

import org...

import com...

import space...

import ...
```

Forbidden examples:

```java
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
```

After editing Java files, run the narrowest relevant compile/test task:

```powershell
.\gradlew.bat :<affected-module>:compileJava --no-daemon
.\gradlew.bat :<affected-module>:compileTestJava --no-daemon
```

Do not optimize imports in files you did not otherwise modify.
