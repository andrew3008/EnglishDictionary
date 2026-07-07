# Release Notes — opus-mcp-server &lt;VERSION&gt;

> Template. Copy to `RELEASE-NOTES.md` inside the release directory and fill placeholders.
> Use real values only for an actual release; keep `&lt;...&gt;` placeholders otherwise.

- **Version:** &lt;e.g. 0.1.0-SNAPSHOT&gt;
- **Date:** &lt;YYYY-MM-DD&gt;
- **Status:** &lt;SNAPSHOT | RELEASE&gt;
- **Compatibility:** &lt;Java 21, MCP stdio, Cursor; note any breaking changes&gt;

## Added
- &lt;new compatible features / read-only tools&gt;

## Changed
- &lt;behavior-preserving changes, build/docs/release workflow&gt;

## Fixed
- &lt;bug fixes&gt;

## Security
- &lt;guardrail/supply-chain/security-contract changes; CVE verdict source&gt;

## Operational notes
- &lt;env var changes, smoke expectations, Cursor config notes&gt;

## Known limitations
- &lt;in-memory budget/rate state, single-user/local-only, etc.&gt;

## Verification commands
```powershell
.\gradlew.bat clean test
.\gradlew.bat verifyLocal
.\gradlew.bat releaseCheck
.\gradlew.bat securityHandoff
.\gradlew.bat releasePackageCheck
```

## Artifacts
- `opus-mcp-server-&lt;VERSION&gt;-all.jar`
- `release-manifest.json`

## Checksums
- SHA-256: `&lt;hash&gt;  opus-mcp-server-&lt;VERSION&gt;-all.jar`

## Rollback notes
- Previous version: &lt;VERSION&gt;
- Point Cursor `mcp.json` `-jar` back to the previous release jar and restart Cursor.
- See [RELEASE.md](RELEASE.md) for the full rollback procedure.
