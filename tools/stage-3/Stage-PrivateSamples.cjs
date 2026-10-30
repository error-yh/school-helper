// Copies only explicitly enumerated task fixtures into the debuggable library test sandbox.
// No uninstall, clear-data, network transfer or device identifier output.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const repo = path.resolve(__dirname, '../..');
const workspace = path.resolve(repo, '../..');
const adb = path.join(workspace, '.tools/android-sdk/platform-tools/adb.exe');
const pkg = 'com.lingion.sleepy.test';
const output=path.join(repo,'docs/stage-3/evidence/device/private-fixture-transfer.json');
if(fs.existsSync(output)) throw new Error('Evidence already exists; do not overwrite it');
const files = [
 ...['a','b','c'].map(s => ['samples', `pdf-${s}.pdf`]),
 ...['a','b','c'].map(s => ['tooling', `pdf-${s}-full-reference.tsv`]),
 ...['a','c'].map(s => ['tooling', `pdf-${s}-unscheduled-reference.tsv`]),
];
function run(args, input) {
 const result = cp.spawnSync(adb, args, {input, maxBuffer:1024*1024, windowsHide:true});
 if(result.status !== 0) throw new Error('Private fixture transfer failed; no data was cleared.');
 return result.stdout.toString('utf8');
}
run(['shell', '-T', `run-as ${pkg} mkdir -p files/stage3-private`]);
const summary = [];
for(const [dir, name] of files) {
 if(!/^[a-z0-9.-]+$/.test(name)) throw new Error('Unexpected fixture name');
 const source = path.join(workspace,'.private/stage-3',dir,name);
 const bytes = fs.readFileSync(source);
 const sha = crypto.createHash('sha256').update(bytes).digest('hex');
 // Windows adb text-mode stdin truncates at Ctrl-Z. ASCII transport is decoded only in the test sandbox.
 run(['shell','-T',`run-as ${pkg} sh -c 'base64 -d > files/stage3-private/${name}'`],bytes.toString('base64'));
 const remote = run(['shell','-T',`run-as ${pkg} sha256sum files/stage3-private/${name}`]).trim().split(/\s+/)[0];
 if(remote !== sha) throw new Error('Private fixture hash mismatch');
 summary.push({fixture:name,bytes:bytes.length,sha256:sha,verified:true});
}
fs.mkdirSync(path.dirname(output),{recursive:true});
fs.writeFileSync(output,JSON.stringify({package:pkg,transport:'authorized local adb stdin; base64 decoded in private test sandbox',identifiersRetained:false,files:summary},null,2));
console.log(`Transferred ${summary.length} private fixtures with matching hashes.`);
