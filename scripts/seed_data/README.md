# Reproducible fixtures

The authoritative fixture generator is the Spring demo service, executed transactionally against PostgreSQL. It assigns synthetic account references and deterministic decimal amounts. All values are synthetic.

Reset through `POST /api/demo/reset` as the reviewer, then call `POST /api/demo/trigger-failure` with `{"totalRecords":1000,"processedCount":800}`. The trigger writes actual processed and pending records; no detector or simulator assumes those counts. The HTTP acceptance test also runs a 17-record batch with six processed records.

Secondary fixtures contain genuinely duplicated reconciliation references and mismatches. Equal payment amounts alone do not define duplicates. Reset is available only in demo mode and preserves the historical audit log.
