# Perplexity Provider Approval Checklist

Gate for approving the Perplexity provider for `research_with_perplexity`. **This checklist is NOT
complete.** Live research must not be enabled for sensitive use until every item is checked and the
named owners sign off. Offline (missing-key) use is already safe and requires no approval.

Related: [decisions/ADR-research-with-perplexity-offline-first.md](decisions/ADR-research-with-perplexity-offline-first.md),
[RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md](RESEARCH-WITH-PERPLEXITY-THREAT-MODEL.md),
[PERPLEXITY-LIVE-GATE.md](PERPLEXITY-LIVE-GATE.md).

## Provider configuration

- [ ] Official API key source confirmed (issued from the official Perplexity account/console).
- [ ] `PERPLEXITY_BASE_URL` confirmed (default `https://api.perplexity.ai`); no proxy injecting tokens.
- [ ] Supported model list confirmed; the configured `PERPLEXITY_MODEL` is valid (default
      `sonar-deep-research`).

## Legal / data handling

- [ ] Provider terms of service reviewed.
- [ ] Privacy policy reviewed.
- [ ] Prompt/response **retention** behavior understood (how long, where, who can access).
- [ ] Training-on-data behavior understood and acceptable (or opted out).
- [ ] Proprietary-code policy approved (what may/may not be sent; default: nothing proprietary).

## Operational limits

- [ ] Provider rate limits understood.
- [ ] Billing/cost limits understood and a budget cap is set.
- [ ] Local `BudgetTracker`/`RateLimiter` settings aligned with provider/cost limits.

## Governance

- [ ] Incident and rollback procedure defined (see operator runbook).
- [ ] Security owner approval recorded: __________ (name / date).
- [ ] Architecture owner approval recorded: __________ (name / date).
- [ ] Operator owner identified: __________ (name / contact).

## Sign-off

Live research stays **disabled/unverified** until all boxes above are checked and the
[PERPLEXITY-LIVE-GATE.md](PERPLEXITY-LIVE-GATE.md) acceptance run passes. No production, security, or
enterprise approval is implied by this document.
