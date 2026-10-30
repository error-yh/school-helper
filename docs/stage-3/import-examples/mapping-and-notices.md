# Invented mapping and notice examples

These examples contain invented course names and places. They exercise pure parsing only: they do not create database IDs, select a semester, confirm a date, or save a course.

## Explicit table mapping

Select a worksheet explicitly, then call `HeaderMappingInspector.inspect(table)`. A proposal is evidence for a human mapping decision, never a confirmed mapping. Coordinates match the readers: real row and column numbers are **one based**, including sparse XLSX coordinates. `ConfirmedColumnMapping(headerRows, columns, ignoredColumns)` records that explicit decision. Empty `headerRows` explicitly declares a headerless table. A title before the selected header remains raw source evidence and an issue.

Header aliases normalize spacing, punctuation, and case; body cells retain their exact literal text. Each tabular source retains actual positive row/column coordinates, the raw literal/cache text, and a separate original formula expression when present. JSON and notice sources retain null table coordinates. `时间` means a complete time range, such as `10:00-11:30`, rather than a start time. `日期` accepts full ISO dates separated by semicolons. `周次` uses the existing week grammar, such as `1,3,5,8`, `1-12(单)`, or `第8至12周`. `节次` preserves noncontiguous values such as `1、3`.

Duplicate aliases require manual correction. A manual map works with unusual headers. An unmapped column remains in sources and review issues unless explicitly ignored. A formula cache never supplies a verified value, even in an ignored column. Merged headers retain their actual anchor; body merges never copy course names into following rows. Two-level headers must have both real rows explicitly confirmed.

The one permitted composite mapping uses one recognized XLSX DATE_TIME column for `DATES` and `START_TIME` (or `END_TIME`). That time component counts as used only when a complete valid time range is parsed. A bare spreadsheet number is never an arbitrary date. Nonzero seconds require correction and are never rounded by this parser.

`meetings.csv` expects two courses, two meetings, one attached CANCEL, and no pending attachment:

| Course key | Meeting | Exact declaration |
| --- | --- | --- |
| demo-a | m1 | Recurring Monday, weeks `{1,3,5,8}`, sections `{1,2}`; CANCEL original date `2026-09-21` |
| demo-b | m1 | Dates `{2026-12-30,2027-01-06}`, time `14:00-16:00` |

Same-title rows without a course key or supplied course/class codes remain separate courses with an identity review issue. Explicit course keys group meetings only within this document. Meeting keys are unique inside their course; conflicting duplicate keys reject the whole document rather than overwriting a row.

An exception attaches only to a unique valid base with both document-local `courseKey` and `meetingKey`. Dangling keys and incomplete bases remain `PendingExceptionAttachment` with the original row and constraints. On exception rows, **an existing mapped empty metadata cell means explicit clear (`""`)**. An absent metadata column or absent sparse XLSX cell means inherit (`null`). For base meetings, empty metadata means unknown. CANCEL/RESCHEDULE require `originalDate`; EXTRA/RESCHEDULE require `targetDate`.

## Notices with an explicit date basis

Use `NoticeParseContext(formatContext, referenceDate, confirmedTermMonday?, confirmedYear?)`. No device clock, timezone, storage, or DAO is consulted. A supplied term anchor must be Monday; a supplied year must be 1 through 9999.

`notices.txt` contains five independent clauses. With reference date `2026-12-30` and confirmed term Monday `2026-09-07`, expect five pending exceptions and zero automatically attached exceptions. Every relative date remains a proposal whose `requiresReview` is always true:

| Clause | Result requiring review |
| --- | --- |
| 明日示例数学…加课 | EXTRA candidate `2026-12-31`, room 示例室A |
| 原2026-10-05…调至2026-10-06… | RESCHEDULE with both explicit dates |
| 第3周周一…停课 | CANCEL candidate `2026-09-21` from the confirmed term anchor |
| 下周三下午第5、6节增加人工智能实验课，第8至12周，在实验楼305 | EXTRA candidate `2027-01-06`, declared weeks `{8,9,10,11,12}`, sections `{5,6}`, room 实验楼305; 下午 remains unresolved and no clock time or teacher is invented |
| 周末示例物理停课 | CANCEL with an unresolved date proposal |

今日/明日/后天 use the explicit reference date. 本周/下周 use its ISO Monday and require a weekday. 周末/近期/下次 remain unresolved. A month/day without a year remains unresolved unless a year or term anchor was supplied. An explicit year is used exactly. For a term anchor, the candidate is the first matching month/day in the anchor year or following year, with an explicit rule and basis; invalid dates are not searched in later years. Dates outside the supplied teaching-week window remain review issues.

Newlines and semicolons separate independent clauses; commas keep a notice's declarations together. Unmatched text remains available for correction. Course-title matching never searches stored courses. Confirmation and any “create a new course” decision belong to a later explicit application step.
