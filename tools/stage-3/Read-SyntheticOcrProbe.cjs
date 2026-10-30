// Reads only the invented Canvas fixture written by the O15 diagnostic test.
// This is not a general device/private-file export utility.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync, spawnSync } = require('node:child_process');
const repo = path.resolve(__dirname, '../..');
const workspace = path.resolve(repo, '../..');
const adb = path.join(workspace, '.tools/android-sdk/platform-tools/adb.exe');
const env = { ...process.env, ANDROID_SDK_HOME: path.join(workspace, '.private'),
  ANDROID_USER_HOME: path.join(workspace, '.private/android-user') };
const options = { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 12000000 };
const expected = '7C15C71BF1EA5404E27FA717AF046F210FF106BA3B87EA3A8FF0FCBBD1A4C071';
const deviceFile = 'files/ocr-synthetic-original.png';
const evidence = path.join(repo, 'docs/stage-3/evidence/O-synthetic-raster-inspection-01.json');
const output = path.join(workspace, '.private/stage-3/analysis/native-synthetic/original-O15.png');
if (fs.existsSync(evidence) || fs.existsSync(output)) throw Error('Preserve existing probe evidence');
let bytes;
try {
  const devices = execFileSync(adb, ['devices'], { ...options, encoding: 'utf8' });
  if (devices.split(/\r?\n/).filter(v => /^\S+\s+device$/.test(v)).length !== 1) {
    throw Error('Device count');
  }
  bytes = execFileSync(adb, ['exec-out', 'run-as', 'com.lingion.sleepy.test', 'cat', deviceFile], options);
} catch { throw Error('Unable to read the single owned synthetic fixture'); }
const actual = crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase();
if (!bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) || actual !== expected) {
  throw Error('Synthetic fixture identity mismatch; no local export or device deletion performed');
}
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, bytes, { flag: 'wx' });
try { execFileSync(adb, ['shell', 'run-as', 'com.lingion.sleepy.test', 'rm', '--', deviceFile], options); }
catch { throw Error('Owned fixture cleanup failed; local verified copy preserved'); }
const remaining = spawnSync(adb, ['shell', 'run-as', 'com.lingion.sleepy.test', 'test', '-e', deviceFile], options);
if (remaining.status !== 1) throw Error('Owned fixture absence not verified');
fs.writeFileSync(evidence, JSON.stringify({ operation: 'Read exact invented O15 Canvas PNG, verify SHA, remove only that device file',
  sha256: actual, bytes: bytes.length, signatureVerified: true, ownedDeviceFilesRemoved: 1,
  remainingOwnedDeviceFiles: 0, privateLocalCopy: '.private/stage-3/analysis/native-synthetic/original-O15.png',
  personalSamplesReadOrTransferred: false }, null, 2) + '\n', { flag: 'wx' });
console.log(`Synthetic raster verified; bytes=${bytes.length}; device fixture removed; remaining=0`);
