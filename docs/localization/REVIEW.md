# Localization Review Register

Automated checks enforce key and placeholder consistency. They cannot establish translation
quality. A fluent human reviewer must replace `Pending` with their name, review date, and the commit
SHA reviewed before a production release can be approved.

| Locale | Resource qualifier | Human review | Reviewer | Commit |
| --- | --- | --- | --- | --- |
| English | `values` | Source approved | 4Luke4 | Pending release commit |
| Italian | `values-it` | Pending | — | — |
| Russian | `values-ru` | Pending | — | — |
| Norwegian Bokmål | `values-nb` | Pending | — | — |
| Spanish | `values-es` | Pending | — | — |
| French | `values-fr` | Pending | — | — |
| German | `values-de` | Pending | — | — |
| Brazilian Portuguese | `values-pt-rBR` | Pending | — | — |
| Simplified Chinese | `values-zh-rCN` | Pending | — | — |
| Japanese | `values-ja` | Pending | — | — |
| Turkish | `values-tr` | Pending | — | — |

The 0.5.0 audio and microphone strings are translated but remain covered by each locale's pending
human-review gate.

Review includes meaning, tone, truncation at 720dp, right glyph rendering, spoken accessibility
labels, substitutions such as `%1$d`, and consistency with Fedora/XFCE terminology.
