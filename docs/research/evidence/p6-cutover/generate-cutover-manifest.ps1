# P6.0 — Deterministic cutover-manifest generator (no production code changes).
# Sources: S-04 inventory, plan §6.1 (30 rows), §21.1 families, S-07 member-inventory, public-control consumers.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')
$outDir = $PSScriptRoot
$asOf = Get-Date -Format 'yyyy-MM-dd'
$capturedAt = Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK'
Push-Location $root
try {
  $head = (git rev-parse HEAD).Trim()
  $branch = (git rev-parse --abbrev-ref HEAD).Trim()
} finally {
  Pop-Location
}

function New-Row {
  param(
    [string]$Section,
    [string]$InventoryId,
    [string]$CurrentName,
    [string]$FinalName,
    [string]$Disposition,
    [string]$OwnerBefore,
    [string]$OwnerAfter,
    [object]$ReadersBefore,
    [object]$ReadersAfter,
    [object]$FilesModules,
    [string]$Diagnostic,
    [string]$VerificationRule,
    [string]$Status = 'PENDING_CUTOVER',
    [hashtable]$Extra = @{}
  )
  $row = [ordered]@{
    section              = $Section
    inventoryId          = $InventoryId
    currentNameOrSymbol  = $CurrentName
    finalNameOrDisposition = $FinalName
    disposition          = $Disposition
    ownerBefore          = $OwnerBefore
    ownerAfter           = $OwnerAfter
    productionReadersBefore = @($ReadersBefore)
    productionReadersAfter  = @($ReadersAfter)
    filesOrModules       = @($FilesModules)
    replacementOrDeletionDiagnostic = $Diagnostic
    verificationRule     = $VerificationRule
    status               = $Status
  }
  foreach ($k in $Extra.Keys) { $row[$k] = $Extra[$k] }
  return [pscustomobject]$row
}

# --- §6.1 closed override list (exactly 30 data rows from plan) ---
$section61 = @(
  @{ id='CONFIG_PROPERTY-040'; cur='platform.tracing.sampling.drop-paths'; fin='platform.tracing.javaagent.sampling.drop-paths'; disp='RENAME_TO_JAVAAGENT'; ob='Agent + Spring'; oa='Agent'; src='Decision 14 §5 Category B' },
  @{ id='CONFIG_PROPERTY-048'; cur='platform.tracing.scrubbing.built-in-rules'; fin='platform.tracing.javaagent.scrubbing.built-in-rules'; disp='RENAME_TO_JAVAAGENT'; ob='Agent + Spring'; oa='Agent'; src='Decision 14 §5 Category B' },
  @{ id='CONFIG_PROPERTY-049'; cur='platform.tracing.scrubbing.enabled'; fin='platform.tracing.javaagent.scrubbing.enabled'; disp='RENAME_TO_JAVAAGENT'; ob='Agent + Spring'; oa='Agent'; src='Decision 14 §5 Category B' },
  @{ id='CONFIG_PROPERTY-043'; cur='platform.tracing.sampling.force-record-header-values'; fin='platform.tracing.javaagent.sampling.force-record-values'; disp='RENAME_SPELLING_AND_OWNER'; ob='Spring'; oa='Agent'; src='Decision 14 §5' },
  @{ id='CONFIG_PROPERTY-044'; cur='platform.tracing.sampling.force-record-values'; fin='platform.tracing.javaagent.sampling.force-record-values'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='Decision 14 §5' },
  @{ id='CONFIG_PROPERTY-042'; cur='platform.tracing.sampling.force-record-header'; fin='platform.tracing.sampling.force-record-header'; disp='KEEP'; ob='Agent'; oa='Agent'; src='S-04 KEEP' },
  @{ id='CONFIG_PROPERTY-002'; cur='platform.tracing.baggage.allowlist-keys'; fin='platform.tracing.javaagent.propagation.baggage.allowlist-keys'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-003'; cur='platform.tracing.baggage.deny-patterns'; fin='platform.tracing.javaagent.propagation.baggage.deny-patterns'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-004'; cur='platform.tracing.baggage.enabled'; fin='platform.tracing.javaagent.propagation.baggage.enabled'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-019'; cur='platform.tracing.propagation.baggage.allowed-keys'; fin='platform.tracing.javaagent.propagation.baggage.allowed-keys'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-020'; cur='platform.tracing.propagation.baggage.deny-patterns'; fin='platform.tracing.javaagent.propagation.baggage.deny-patterns'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-021'; cur='platform.tracing.propagation.baggage.enabled'; fin='platform.tracing.javaagent.propagation.baggage.enabled'; disp='RENAME_TO_JAVAAGENT'; ob='Agent'; oa='Agent'; src='§6 namespace taxonomy; S-04 MOVE_OWNERSHIP' },
  @{ id='CONFIG_PROPERTY-024'; cur='platform.tracing.propagation.outbound.enabled'; fin='platform.tracing.propagation.outbound.enabled'; disp='DELETE_AGENT_KEEP_SPRING'; ob='Agent + Spring'; oa='Spring'; src='S-04 DUPLICATED_ACTIVE' },
  @{ id='CONFIG_PROPERTY-025'; cur='platform.tracing.propagation.outbound.propagate-force-trace'; fin='platform.tracing.propagation.outbound.propagate-force-trace'; disp='DELETE_AGENT_KEEP_SPRING'; ob='Agent + Spring'; oa='Spring'; src='S-04 DUPLICATED_ACTIVE' },
  @{ id='CONFIG_PROPERTY-026'; cur='platform.tracing.propagation.outbound.propagate-qa-trace'; fin='platform.tracing.propagation.outbound.propagate-qa-trace'; disp='DELETE_AGENT_KEEP_SPRING'; ob='Agent + Spring'; oa='Spring'; src='S-04 DUPLICATED_ACTIVE' },
  @{ id='CONFIG_PROPERTY-027'; cur='platform.tracing.propagation.outbound.propagate-request-id'; fin='platform.tracing.propagation.outbound.propagate-request-id'; disp='DELETE_AGENT_KEEP_SPRING'; ob='Agent + Spring'; oa='Spring'; src='S-04 DUPLICATED_ACTIVE' },
  @{ id='CONFIG_PROPERTY-028'; cur='platform.tracing.propagation.outbound.trusted-host-patterns'; fin='platform.tracing.propagation.outbound.trusted-host-patterns'; disp='DELETE_AGENT_KEEP_SPRING'; ob='Agent + Spring'; oa='Spring'; src='S-04 DUPLICATED_ACTIVE' },
  @{ id='RESOURCE_ATTRIBUTE-001'; cur='platform.tracing.policy.version'; fin='platform.tracing.resource.applied-policy-version'; disp='RENAME_RESOURCE_ATTRIBUTE'; ob='Agent'; oa='Agent'; src='namespace conflict §6' },
  @{ id='CONFIG_PROPERTY-036'; cur='platform.tracing.resource.policy-version'; fin='platform.tracing.resource.policy-version'; disp='KEEP'; ob='Spring'; oa='Spring'; src='separate configuration key' },
  @{ id='DOCUMENTED_ONLY_KEY-001'; cur='platform.tracing.semconv.validation.mode'; fin='platform.tracing.semantic.validation-mode'; disp='RENAME'; ob='External'; oa='Spring'; src='P7 governance' },
  @{ id='CONFIG_PROPERTY-060'; cur='platform.tracing.semantic.validation-mode'; fin='platform.tracing.semantic.validation-mode'; disp='KEEP'; ob='Spring'; oa='Spring'; src='active Spring semantic-layer key' },
  @{ id='FIXTURE_ONLY_KEY-001'; cur='platform.tracing.async.enabled'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-002'; cur='platform.tracing.context-propagation.async.enabled'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-003'; cur='platform.tracing.context-propagation.thread-pool-task-executor.enabled'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-004'; cur='platform.tracing.enabled'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-005'; cur='platform.tracing.policy.sampling.ratio'; fin='platform.tracing.policy.sampling.ratio'; disp='DELETE_FIXTURE_INTRODUCE_CANONICAL_CATEGORY_A'; ob='Fixture'; oa='Agent'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-006'; cur='platform.tracing.policy.sampling.route-ratio'; fin='platform.tracing.policy.sampling.route-ratios'; disp='DELETE_FIXTURE_INTRODUCE_CANONICAL_CATEGORY_A'; ob='Fixture'; oa='Agent'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-007'; cur='platform.tracing.sdk.mode'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' },
  @{ id='FIXTURE_ONLY_KEY-008'; cur='platform.tracing.service.k8s-pod-uid'; fin='DELETE'; disp='DELETE'; ob='Fixture'; oa='—'; src='S-04 FIXTURE_ONLY' }
  # CONFIG_PROPERTY-032 (platform.tracing.propagation.w3c.diagnostics-only) removed from this
  # override list: P6.1 CUTOVER_COMPLETE — dead PROP_W3C_DIAGNOSTICS_ONLY constant (NO_READER)
  # deleted from PropagationDefaults.java; S-04 inventory regenerated (78 -> 77 CONFIG_PROPERTY).
)
$overrideById = @{}
foreach ($o in $section61) { $overrideById[$o.id] = $o }

$inventoryPath = Join-Path $root 'docs\research\evidence\tracing-configuration-inventory\inventory.json'
$inventory = Get-Content -Raw -Encoding UTF8 $inventoryPath | ConvertFrom-Json
$props = @($inventory | Where-Object { $_.kind -eq 'CONFIG_PROPERTY' } | Sort-Object id)
$envs  = @($inventory | Where-Object { $_.kind -eq 'ENV_VARIABLE' } | Sort-Object id)
if ($props.Count -ne 77) { throw "Expected 77 CONFIG_PROPERTY, got $($props.Count)" }
if ($envs.Count -ne 29)  { throw "Expected 29 ENV_VARIABLE, got $($envs.Count)" }
if ($section61.Count -ne 29) { throw "Expected 29 §6.1 rows, got $($section61.Count)" }

$rows = New-Object System.Collections.Generic.List[object]

foreach ($p in $props) {
  $ov = $overrideById[$p.id]
  if ($null -ne $ov) {
    $final = $ov.fin
    $disp = $ov.disp
    $ob = $ov.ob
    $oa = $ov.oa
    $diag = "section 6.1 override ($($ov.src)) supersedes S-04 proposedDisposition=$($p.proposedDisposition) proposedTargetName=$($p.proposedTargetName)"
  } else {
    $final = $p.proposedTargetName
    $disp = [string]$p.proposedDisposition
    $ob = [string]$p.lifecycleOwner
    $oa = [string]$p.proposedTargetOwner
    $diag = "Apply S-04 disposition exactly; aliases/dual-read/silent-fallback forbidden"
  }
  $files = @()
  if ($p.declarations) { $files += @($p.declarations) }
  if ($p.readers) { $files += @($p.readers) }
  $rows.Add((New-Row -Section 'CONFIG_PROPERTY' -InventoryId $p.id -CurrentName $p.exactName `
      -FinalName $final -Disposition $disp -OwnerBefore $ob -OwnerAfter $oa `
      -ReadersBefore $p.readers -ReadersAfter @("target owner readers only: $oa") `
      -FilesModules $files -Diagnostic $diag `
      -VerificationRule 'tracingConfigurationInventoryVerify + legacy-name detector zero hit for currentName when disposition is RENAME/DELETE' `
      -Extra @{ valueType=$p.valueType; s04ProposedDisposition=$p.proposedDisposition; section61Override=($null -ne $ov) }))
}

foreach ($e in $envs) {
  $rows.Add((New-Row -Section 'ENV_VARIABLE' -InventoryId $e.id -CurrentName $e.exactName `
      -FinalName $e.proposedTargetName -Disposition ([string]$e.proposedDisposition) `
      -OwnerBefore ([string]$e.lifecycleOwner) -OwnerAfter ([string]$e.proposedTargetOwner) `
      -ReadersBefore $e.readers -ReadersAfter @("target owner readers only: $($e.proposedTargetOwner)") `
      -FilesModules (@($e.declarations) + @($e.readers)) `
      -Diagnostic 'Apply S-04 env disposition exactly; no relaxed dual binding' `
      -VerificationRule 'environment-variables.json count=29 and zero legacy env alias readers' `
      -Extra @{ valueType=$e.valueType; s04ProposedDisposition=$e.proposedDisposition }))
}

$i = 0
foreach ($o in $section61) {
  $i++
  $rows.Add((New-Row -Section 'SECTION_6_1_OVERRIDE' -InventoryId $o.id -CurrentName $o.cur `
      -FinalName $o.fin -Disposition $o.disp -OwnerBefore $o.ob -OwnerAfter $o.oa `
      -ReadersBefore @('see CONFIG_PROPERTY/ENV row for same inventoryId') `
      -ReadersAfter @("ownerAfter=$($o.oa)") `
      -FilesModules @('plan section 6.1 table', 'docs/research/evidence/tracing-configuration-inventory/inventory.json') `
      -Diagnostic "Closed section 6.1 exception #$i/30; source=$($o.src)" `
      -VerificationRule 'P6.1 must apply this override before inventory regeneration; mismatch = FAIL' `
      -Extra @{ overrideIndex=$i; sourceOfOverride=$o.src }))
}

$families = @(
  @{ n=1; name='RefreshScope reconcile'; del='TracingRefreshScopeAutoConfiguration, RuntimeConfigApplier, direct reconcile'; repl='Config Server/DEV complete snapshot -> private writer'; proof='zero refs + EnvironmentChangeEvent is trigger only' },
  @{ n=2; name='Legacy Actuator mutation'; del='generic/production-capable @WriteOperation, mutation-enabled, dormant writer bean'; repl='typed local-only DEV_ACTUATOR operations'; proof='context/metadata/HTTP absence outside exact posture' },
  @{ n=3; name='Partial Control protocol'; del='legacy applyPolicy, coercion codec, ignored fields, public control DTOs'; repl='private complete applySnapshot + readAcceptedComplete'; proof='exact MBeanInfo/codec tests + zero refs' },
  @{ n=4; name='Platform domain JMX writes'; del='approved E1/E7 DELETE set; baseline 16 semantic mutations'; repl='private writer only; platform read-only'; proof='exact before/after contract verifier' },
  @{ n=5; name='PerfAdminController'; del='HTTP admin mutation and dead ObjectNames'; repl='no production replacement; target perf fixtures'; proof='module scan + perf scenarios green' },
  @{ n=6; name='Test/benchmark direct'; del='direct domain mutations and legacy clients'; repl='accepted-store/private-writer test fixtures'; proof='source scan + tests/JMH green' }
)
foreach ($f in $families) {
  $rows.Add((New-Row -Section 'MUTATION_FAMILY' -InventoryId ("MUTATION_FAMILY-$('{0:D2}' -f $f.n)") `
      -CurrentName $f.name -FinalName $f.repl -Disposition 'DELETE_FAMILY' `
      -OwnerBefore 'legacy multi-writer surface' -OwnerAfter 'Agent accepted owner + private writer' `
      -ReadersBefore @($f.del) -ReadersAfter @($f.repl) `
      -FilesModules @('plan section 21.1') -Diagnostic $f.del `
      -VerificationRule $f.proof -Extra @{ familyIndex=$f.n }))
}

$membersPath = Join-Path $root 'docs\research\evidence\jmx-read-only-delta\member-inventory.json'
$membersDoc = Get-Content -Raw -Encoding UTF8 $membersPath | ConvertFrom-Json
$members = @($membersDoc.members)
if ($members.Count -ne 63) { throw "Expected 63 JMX members, got $($members.Count)" }
foreach ($m in $members) {
  $rows.Add((New-Row -Section 'JMX_E1_E7_DELTA' -InventoryId $m.baselineMemberId `
      -CurrentName ("{0}#{1}:{2}" -f $m.objectName, $m.kind, $m.name) `
      -FinalName ([string]$m.targetDisposition) -Disposition ([string]$m.targetDisposition) `
      -OwnerBefore ([string]$m.currentImplementationOwner) -OwnerAfter ([string]$m.migrationOwner) `
      -ReadersBefore $m.currentRepositoryConsumers -ReadersAfter $m.replacementTargetMemberIds `
      -FilesModules @($m.currentImplementationOwner, 'docs/research/evidence/jmx-read-only-delta/member-inventory.json') `
      -Diagnostic ([string]$m.reason) `
      -VerificationRule ([string]$m.verification) `
      -Extra @{
        kind=$m.kind; name=$m.name; mutating=$m.mutating; writable=$m.writable
        replacementTargetMemberIds=$m.replacementTargetMemberIds
        zeroReferenceProof=$m.zeroReferenceProof
        migrationRequirement=$m.migrationRequirement
      }))
}

$consumerPath = Join-Path $root 'docs\research\evidence\public-control-protocol-consumers\consumer-inventory.json'
$consumers = Get-Content -Raw -Encoding UTF8 $consumerPath | ConvertFrom-Json
foreach ($t in @($consumers.publicTypes)) {
  $rows.Add((New-Row -Section 'PUBLIC_CONTROL_TYPE' -InventoryId $t.fqcn `
      -CurrentName $t.fqcn -FinalName 'DELETE_AT_P6' -Disposition 'DELETE_PUBLIC_CONTROL_TYPE' `
      -OwnerBefore 'platform-tracing-api' -OwnerAfter '-' `
      -ReadersBefore $t.productionMainConsumersOutsideApi `
      -ReadersAfter @() `
      -FilesModules (@($t.productionMainConsumersOutsideApi) + @('platform-tracing-api/.../api/control/protocol')) `
      -Diagnostic 'Public control DTOs/protocol types deleted with partial protocol; no external app consumers found in-repo' `
      -VerificationRule 'zero import of api.control.protocol outside allowlisted evidence/docs after P6.5' `
      -Extra @{ kind=$t.kind; consumerSites=@($t.productionMainConsumersOutsideApi) }))
}
foreach ($site in @($consumers.productionMainImportSites)) {
  $rows.Add((New-Row -Section 'PUBLIC_CONTROL_CONSUMER_SITE' -InventoryId $site `
      -CurrentName $site -FinalName 'REWRITE_OR_DELETE' -Disposition 'REMOVE_PUBLIC_PROTOCOL_IMPORT' `
      -OwnerBefore 'internal product consumer' -OwnerAfter 'private applySnapshot/readAcceptedComplete consumers only' `
      -ReadersBefore @($site) -ReadersAfter @() -FilesModules @($site) `
      -Diagnostic 'Internal product consumer of public control protocol; must be deleted or rewritten in P6.5' `
      -VerificationRule 'file must not import space.br1440.platform.tracing.api.control.protocol after cutover'))
}

# Deployment / fixture occurrence scan for platform.tracing.* in key trees
$scanRoots = @(
  'platform-tracing-perf-harness',
  'platform-tracing-perf-tests',
  'platform-tracing-e2e-tests',
  'platform-tracing-samples',
  'platform-tracing-collector-config'
)
$occIndex = 0
$occurrenceKeys = New-Object 'System.Collections.Generic.HashSet[string]'
foreach ($sr in $scanRoots) {
  $dir = Join-Path $root $sr
  if (-not (Test-Path $dir)) { continue }
  $files = Get-ChildItem -Path $dir -Recurse -File -Include *.yaml,*.yml,*.properties,*.env,*.java,*.md,*.json -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\|\\node_modules\\|\\\.git\\' }
  foreach ($file in $files) {
    $text = Get-Content -Raw -ErrorAction SilentlyContinue $file.FullName
    if (-not $text) { continue }
    $matches = [regex]::Matches($text, 'platform\.tracing\.[A-Za-z0-9_.*\-]+')
    if ($matches.Count -eq 0) { continue }
    $rel = $file.FullName.Substring($root.Path.Length + 1).Replace('\','/')
    $uniq = ($matches | ForEach-Object { $_.Value } | Sort-Object -Unique)
    foreach ($name in $uniq) {
      $key = ($rel + '|' + $name)
      if (-not $occurrenceKeys.Add($key)) { continue }
      $occIndex++
      $rows.Add((New-Row -Section 'DEPLOYMENT_OR_FIXTURE_OCCURRENCE' `
          -InventoryId ("OCC-$('{0:D4}' -f $occIndex)") `
          -CurrentName $name -FinalName 'APPLY_PROPERTY_ROW_DISPOSITION' `
          -Disposition 'REWRITE_TO_FINAL_NAME_OR_DELETE' `
          -OwnerBefore $sr -OwnerAfter $sr `
          -ReadersBefore @($rel) -ReadersAfter @($rel) -FilesModules @($rel) `
          -Diagnostic "Occurrence in $sr; disposition follows matching CONFIG_PROPERTY/ENV/§6.1 row for exactName" `
          -VerificationRule 'P6.9 deployment rewrite: file must contain final names only; zero legacy exactName' `
          -Extra @{ scanRoot=$sr; relativePath=$rel }))
    }
  }
}

$rows.Add((New-Row -Section 'LEGACY_NAME_DETECTOR' -InventoryId 'LEGACY_DETECTOR-001' `
    -CurrentName 'legacy-name detector (not yet implemented)' `
    -FinalName 'exact-name fail-fast detector required by P6.1' `
    -Disposition 'ADD' `
      -OwnerBefore '-' -OwnerAfter 'build/test fitness' `
    -ReadersBefore @() -ReadersAfter @('inventory verification task') `
    -FilesModules @('P6.1 todo p6-legacy-name-detector') `
    -Diagnostic 'Detector must fail on legacy exactName outside allowlist (baseline/migration/changelog evidence only)' `
    -VerificationRule 'deterministic inventory verification; allowlist only for baseline/migration/changelog evidence' `
    -Status 'PENDING_IMPLEMENTATION'))

# Helm charts absent — record explicit absence row
$rows.Add((New-Row -Section 'DEPLOYMENT_HELM' -InventoryId 'HELM-ABSENT-001' `
    -CurrentName 'Helm Chart.yaml / charts tree' -FinalName 'N/A' `
    -Disposition 'ABSENT_IN_REPOSITORY' `
      -OwnerBefore '-' -OwnerAfter '-' `
      -ReadersBefore @() -ReadersAfter @() `
      -FilesModules @() `
      -Diagnostic 'No Helm chart tree present; K8s-like config lives under platform-tracing-perf-harness/config/k8s' `
    -VerificationRule 'P6.9 must not invent Helm charts; update existing k8s/perf fixtures only' `
    -Status 'RECORDED'))

$counts = [ordered]@{
  CONFIG_PROPERTY = @($rows | Where-Object section -eq 'CONFIG_PROPERTY').Count
  ENV_VARIABLE = @($rows | Where-Object section -eq 'ENV_VARIABLE').Count
  SECTION_6_1_OVERRIDE = @($rows | Where-Object section -eq 'SECTION_6_1_OVERRIDE').Count
  MUTATION_FAMILY = @($rows | Where-Object section -eq 'MUTATION_FAMILY').Count
  JMX_E1_E7_DELTA = @($rows | Where-Object section -eq 'JMX_E1_E7_DELTA').Count
  PUBLIC_CONTROL_TYPE = @($rows | Where-Object section -eq 'PUBLIC_CONTROL_TYPE').Count
  PUBLIC_CONTROL_CONSUMER_SITE = @($rows | Where-Object section -eq 'PUBLIC_CONTROL_CONSUMER_SITE').Count
  DEPLOYMENT_OR_FIXTURE_OCCURRENCE = @($rows | Where-Object section -eq 'DEPLOYMENT_OR_FIXTURE_OCCURRENCE').Count
  LEGACY_NAME_DETECTOR = @($rows | Where-Object section -eq 'LEGACY_NAME_DETECTOR').Count
  DEPLOYMENT_HELM = @($rows | Where-Object section -eq 'DEPLOYMENT_HELM').Count
  totalRows = $rows.Count
}

$required = @{
  CONFIG_PROPERTY = 77
  ENV_VARIABLE = 29
  SECTION_6_1_OVERRIDE = 29
  MUTATION_FAMILY = 6
  JMX_E1_E7_DELTA = 63
}
$mismatches = @()
foreach ($k in $required.Keys) {
  if ($counts[$k] -ne $required[$k]) {
    $mismatches += "$k expected $($required[$k]) got $($counts[$k])"
  }
}
if ($mismatches.Count -gt 0) { throw ("Count mismatch: " + ($mismatches -join '; ')) }

$manifest = [ordered]@{
  gate = 'P6.0'
  name = 'Machine-readable coherent cutover execution manifest'
  asOf = $asOf
  capturedAt = $capturedAt
  status = 'MANIFEST_RECORDED'
  productionCodeChangedByThisGate = $false
  repository = @{
    head = $head
    branch = $branch
  }
  preconditions = @{
    p4FormalClosure = 'FORMALLY_CLOSED'
    e7JmxDelta = 'APPROVE_EXACT_JMX_DELTA'
    publicControlDeletionPrerequisite = 'PREREQUISITE_CLOSED'
    p5VerificationPackage = 'PENDING'
    note = 'P6.0 is pre-code-change inventory. P6 production cutover remains blocked until P5.9 PASS; residuals from P5.7/P5.8 recorded in p5-execution baseline.'
  }
  sources = @{
    s04Inventory = 'docs/research/evidence/tracing-configuration-inventory/inventory.json'
    section61 = 'plan section 6.1 (30 closed override rows)'
    mutationFamilies = 'plan section 21.1'
    jmxDelta = 'docs/research/evidence/jmx-read-only-delta/member-inventory.json'
    publicControlConsumers = 'docs/research/evidence/public-control-protocol-consumers/consumer-inventory.json'
    categoryABinding = 'docs/analysis/platform-tracing-configuration/14-category-a-binding-decision.md'
  }
  requiredCounts = $required
  actualCounts = $counts
  forbidden = @(
    'wildcard migration',
    'aliases',
    'relaxed dual binding',
    'silent fallback',
    'independent Agent/Spring switch'
  )
  rows = $rows
}

$jsonPath = Join-Path $outDir 'cutover-manifest.json'
$summaryPath = Join-Path $outDir 'decision-summary.json'
$manifest | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 $jsonPath

$summary = [ordered]@{
  gate = 'P6.0'
  status = 'MANIFEST_RECORDED'
  asOf = $asOf
  capturedAt = $capturedAt
  head = $head
  branch = $branch
  productionCodeChangedByThisGate = $false
  requiredCounts = $required
  actualCounts = $counts
  countCheck = 'PASS'
  evidence = @{
    manifest = 'docs/research/evidence/p6-cutover/cutover-manifest.json'
    generator = 'docs/research/evidence/p6-cutover/generate-cutover-manifest.ps1'
  }
  blockersForP6CodeCutover = @(
    'p5-verification-package (P5.9) still pending',
    'legacy-name detector not yet implemented (LEGACY_DETECTOR-001)',
    'macro perf M10/PerfAdmin rewrite residual from P5.8'
  )
  nextGate = 'P6.1 property/env and namespace cutover (uses this manifest as checklist)'
  p61PropertyDispositionProgress = @{
    completed = @(
      @{ id = 'CONFIG_PROPERTY-032'; action = 'DELETE'; evidence = 'PropagationDefaults.java PROP_W3C_DIAGNOSTICS_ONLY removed; S-04 inventory regenerated 78->77; TracingConfigurationInventoryGeneratorTest PASS' }
    )
    deferredToDownstreamTask = @(
      @{ ids = @('CONFIG_PROPERTY-002','CONFIG_PROPERTY-003','CONFIG_PROPERTY-004','CONFIG_PROPERTY-019','CONFIG_PROPERTY-020','CONFIG_PROPERTY-021'); reason = 'Baggage families collide across two independent production code paths (BaggageExtensionConfig span-attribute allowlist vs PropagationDefaults/BaggagePropagationCustomizer W3C propagation); resolving the family conflict is explicitly scoped to p6-namespace-conflicts (P6.1), not a pure name rename'; deferredTo = 'p6-namespace-conflicts' }
      @{ ids = @('CONFIG_PROPERTY-024','CONFIG_PROPERTY-025','CONFIG_PROPERTY-026','CONFIG_PROPERTY-027','CONFIG_PROPERTY-028'); reason = 'Requires atomic Agent+Spring reader switch and depends on RuntimeConfigApplier reaching zero-ref (P6.10); deleting the Agent-side mirror in isolation would violate the one-unreleased-train invariant'; deferredTo = 'p6-agent-parser-cutover / p6-spring-binding-cutover' }
      @{ ids = @('CONFIG_PROPERTY-040','CONFIG_PROPERTY-043','CONFIG_PROPERTY-044'); reason = 'Same atomic Agent+Spring switch dependency as outbound rows; force-record spelling+ownership merge additionally requires the P6.1 namespace-conflict resolution'; deferredTo = 'p6-agent-parser-cutover / p6-spring-binding-cutover / p6-namespace-conflicts' }
      @{ ids = @('CONFIG_PROPERTY-048','CONFIG_PROPERTY-049'); reason = 'Spring TracingProperties.Scrubbing feeds the still-active Category B legacy runtime-mutation path (RuntimeConfigApplier.updateScrubbingPolicy -> PlatformScrubbingControl -> ScrubbingPolicyHolder); removing the Spring binding before that path is deleted would silently break live runtime mutation. Explicitly reserved for P6.7 (delete Category B ScrubbingPolicyHolder)'; deferredTo = 'p6-scrubbing-category-b-deletion (plan section 20 P6.7)' }
    )
  }
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $summaryPath

Write-Host "Wrote $jsonPath"
Write-Host "Wrote $summaryPath"
Write-Host ("totalRows={0} props={1} envs={2} s61={3} families={4} jmx={5} occ={6}" -f `
  $counts.totalRows, $counts.CONFIG_PROPERTY, $counts.ENV_VARIABLE, $counts.SECTION_6_1_OVERRIDE, `
  $counts.MUTATION_FAMILY, $counts.JMX_E1_E7_DELTA, $counts.DEPLOYMENT_OR_FIXTURE_OCCURRENCE)
