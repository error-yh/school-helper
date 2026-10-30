# Actual Excel producer fixture

`xlsx/excel-produced-synthetic.xlsx` contains only invented records. It was created in a new isolated Microsoft Excel 16.0 workbook; no existing document was opened. It is a reader compatibility fixture, not a student timetable or evidence that import UI works.

SHA256: `9C0FF5816A868DA4E00B0A59F0C1412B6EB1482BF7EF8612668159ECA07CBB71`.

One worksheet, `Synthetic courses`, has nine headers and two data rows. Mathematics declares weekday 1, exact weeks {1,3,5,8}, exact sections {1,3}, 08:00–09:00, synthetic teacher/room A. Laboratory declares date 2027-01-01, 13:00–14:30, synthetic teacher/room B. This is intentionally a literal-reader fixture; the column names are not a promise of automatic mapping or archive grouping. It contains no formulas or external relationships.

Excel wrote numeric time fractions `0.33333333333333331`, `0.375`, `0.54166666666666663`, `0.60416666666666663` and date serial `46388`. It also wrote default revision/markup-compatibility/style extension metadata. These are used to test actual producer compatibility rather than only handcrafted XML.

The original generated file remains private. Before publication, `Sanitize-SyntheticExcelFixture.ps1` parsed its ten XML/rels parts without disk extraction, rejected unexpected parts/formulas/external relationships, replaced core creator/modified metadata, cleared company/manager values, replaced the absolute-path hint with `C:\SyntheticFixture\`, and replaced document/revision UID values with a deterministic synthetic UID. Extension-identifying URIs and course data/styles were retained. ZIP timestamps are fixed. The scrub verifies that collected private metadata values no longer occur, without printing them. These checks cover this generated fixture; they are not a general document anonymizer.

The generating and scrubbing scripts are in `tools/stage-3/`. Compatibility tests and acceptance are pending at fixture creation; do not infer success from the presence of this file.
