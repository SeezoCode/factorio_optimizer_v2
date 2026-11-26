# Py science campaign: what was tried

Newest last. Hours are game hours unless said otherwise. Each entry: what, why, result, verdict (worked / did not /
open). Context and the help levels: [CAMPAIGN_CONTEXT.md](CAMPAIGN_CONTEXT.md).

## 2026-11-26

- **Set-up in the cloud container** (Factorio 2.1.24 headless with Py, Java 25, `FO_NO_IMAGES`). Worked; see
  [CLOUD_SETUP.md](CLOUD_SETUP.md). 2.1.25 does not load Py (pipe sprite variation mismatch).
- **Solve queue m2**: all 11 sciences with a 2% planner margin (the margin layouts are the ones that held logistic
  and chemical science on the desktop). Running.
- **Automation science, L3, 10 + 10 minutes**: PASS (13 machines). A smoke test of the launcher and the rule flags.
- **Solve queue r2 (robot layouts)**, started 23:25 UTC beside m2 and **stopped at once: the user wants belts only**
  (sinks allowed). Its plan was: `blocks.max_machines` 2 by machines,
  `bot_links` 1.0 (every item link between blocks by logistic robots), `overflow_after_stacks` 2,
  `provider_until_stacks` 2, 2% margin, 2 threads. Why: the go/no-go nights (2026-11-13 to 11-15) found belts do not
  share out a metered supply while robots do; the robot-built logistic factory made 96 of 97 packs due from a warm
  start. Order: logistic, chemical, py-2, production, py-3, utility, py-4, space, military, py-1.
- Builds page published: https://claude.ai/artifact/7rqXsL9UZFaMF8m7fdcyEk (drawn from each layout's blueprint).
- **Solve queue s2 (belts with local sinks)**, started 23:26 UTC: `blocks.local_sinks` 1000 (every by-product ends in
  a chest or Py's fluid void beside its block). Why: fewer corridor links (Py production science: 312 of 332 item
  outputs local in an earlier run), so big factories stitch sooner; the user allows sinks. Big sciences first.
