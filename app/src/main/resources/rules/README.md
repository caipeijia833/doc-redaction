# Mainland China administrative-division lexicon

`cn-administrative-divisions-2023.tsv.gz` is generated offline by
`tools/generate-cn-division-lexicon.py` from the hash-pinned `2.7.0` release of
`modood/Administrative-divisions-of-China`.

- Data baseline: 2023-06-30
- Runtime entries after generic-name filtering: 44,712
- Levels: 31 province-level records plus aliases, 342 prefecture-level records,
  2,978 county-level records, and 41,352 township-level source records
- Generated file SHA-256:
  `8c68aca1b013514c04c79afa1ee929ed94a2ea5edc4c357d1726e863ca72d849`
- License: WTFPL; exact upstream text is embedded as
  `ADMINISTRATIVE_DIVISIONS_LICENSE.txt`
- Runtime network access: none

This snapshot is deliberately described as a 2023 baseline, not as a current
official 2026 code table. The upstream project says it stopped updating after
the National Bureau of Statistics stopped publishing concrete codes from
October 2024. The generator rejects source files whose SHA-256 does not match
the pinned archive retained under
`audit/source-archives/20260814-cn-division-2.7.0/`.

Names that are common words are not accepted blindly: county names are
disambiguated and township/subdistrict names require address context or a
nearby higher-level division. This reduces false positives but does not remove
the need for human review.
