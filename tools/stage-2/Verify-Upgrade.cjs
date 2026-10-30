// Read only the private timetable backup. Public evidence contains counts and hashes, never rows/IDs.
const { DatabaseSync } = require('node:sqlite');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const [inspection, beforeFile, evidenceFile] = process.argv.slice(2);
const hash = value => createHash('sha256').update(value).digest('hex');
const before = JSON.parse(fs.readFileSync(beforeFile, 'utf8').replace(/^\uFEFF/, ''));
const files = fs.readdirSync(path.join(inspection, 'databases')).filter(name => /^timetable_[A-Za-z0-9_-]+\.db$/.test(name));
if (files.length !== 1) throw new Error('Expected exactly one private timetable database');
const db = new DatabaseSync(path.join(inspection, 'databases', files[0]), { readOnly: true });
const version = db.prepare('PRAGMA user_version').get().user_version;
const rows = db.prepare('SELECT id,courseName,day,startNode,step,startWeek,endWeek,type FROM courses ORDER BY id').all();
const dataHash = hash(JSON.stringify(rows));
const profileHash = hash(fs.readFileSync(path.join(inspection, 'shared_prefs', 'timetable_local_profile.xml')));
const check = condition => { if (!condition) throw new Error('Lossless upgrade verification failed; private backup retained'); };
check(version === 2 && rows.length === before.legacyRows && dataHash === before.legacyDataSha256);
check(profileHash === before.profilePreferenceSha256);
for (const row of rows) {
  const course = db.prepare('SELECT * FROM scheduled_courses WHERE id=?').get(row.id);
  const meetings = db.prepare('SELECT * FROM course_meetings WHERE courseId=?').all(row.id);
  check(course?.courseName === row.courseName && meetings.length === 1);
  const meeting = meetings[0];
  check(meeting.id === row.id && meeting.day === row.day);
  const weeks = db.prepare('SELECT week FROM meeting_weeks WHERE meetingId=? ORDER BY week').all(meeting.id).map(item => item.week);
  const expectedWeeks = [];
  for (let week = row.startWeek; week <= row.endWeek; week++) {
    if (row.type === 1 ? week % 2 === 1 : row.type === 2 ? week % 2 === 0 : true) expectedWeeks.push(week);
  }
  const sections = db.prepare('SELECT section FROM meeting_sections WHERE meetingId=? ORDER BY section').all(meeting.id).map(item => item.section);
  check(JSON.stringify(weeks) === JSON.stringify(expectedWeeks));
  check(JSON.stringify(sections) === JSON.stringify(Array.from({ length: row.step }, (_, index) => row.startNode + index)));
}
db.close();
fs.writeFileSync(evidenceFile, JSON.stringify({
  version, legacyRows: rows.length, legacyDataSha256: dataHash, profilePreferenceSha256: profileHash,
  archiveUnchanged: true, profileUnchanged: true, everyLegacyRowMappedWithExactRules: true,
  noAccountDataCollected: true, passed: true,
}, null, 2) + '\n');
process.stdout.write(`Device upgrade verified: schema ${version}, ${rows.length} archived rows preserved, profile unchanged.\n`);
