# Sampling, Force Sampling, and Scrubbing

## Sampling

Sampling must be deterministic and explainable.

Support may include:

- default ratio
- route-specific ratios
- force-sampling controls
- kill switches
- runtime policy updates

Rules:

- ratio bounds are validated
- route precedence is explicit
- normalized route templates are used
- empty mutations are rejected
- runtime mutation is fail-closed unless explicitly enabled
- force sampling does not bypass scrubbing or export safety
- no hidden conflict between environment sampler settings and platform sampler policy
- applied state is readable
- rejected updates preserve last-known-good state

Sampling tests that require export must set deterministic values such as ratio `1.0`.

Tests for drop behavior must use deterministic drop policy, not probability.

## Force Sampling

Force sampling is a high-risk control because it can amplify telemetry volume and sensitive-data exposure.

Requirements:

- strict accepted values
- bounded header length
- disabled or gateway-controlled by default according to policy
- no arbitrary truthy strings
- no bypass of scrubbing
- no bypass of export kill switch
- bounded metrics for accepted/rejected requests
- documented trust assumptions
- deterministic tests for ratio `0` plus force-on behavior

Do not interpret force headers as authorization.

## Scrubbing and PII

Scrubbing is a production safety mechanism.

The implementation must state its scope precisely.

For example, a span-attribute scrubbing rule does not automatically protect:

- events
- links
- baggage
- resources
- logs
- metrics

Required diagnostics should include safe information such as:

- active rule names
- skipped unknown rule names
- rule-set fingerprint
- critical rule failures
- disabled/enabled state

Diagnostics must never include raw sensitive values.

Scrubbing rules must be deterministic.

Unknown configured rules must not disappear silently.

Force-sampled spans must still pass through scrubbing.

