// Removes only this task's explicitly named temporary student fixtures and derived reports.
const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process');
const repo=path.resolve(__dirname,'../..'),workspace=path.resolve(repo,'../..');
const adb=path.join(workspace,'.tools/android-sdk/platform-tools/adb.exe');
const pkg='com.lingion.sleepy.test';
const names=[...['a','b','c'].map(s=>`pdf-${s}.pdf`),
 ...['a','b','c'].map(s=>`pdf-${s}-full-reference.tsv`),
 ...['a','c'].map(s=>`pdf-${s}-unscheduled-reference.tsv`),
 ...['a','b','c'].map(s=>`android-pdf-${s}-parsed-private.txt`),'transport-probe.bin'];
for(const name of names) if(!/^[a-z0-9.-]+$/.test(name)) throw new Error('Unexpected task fixture');
const command=`run-as ${pkg} rm -f ${names.map(n=>'files/stage3-private/'+n).join(' ')}`;
const removed=cp.spawnSync(adb,['shell','-T',command],{windowsHide:true});
if(removed.status!==0)throw new Error('Private fixture cleanup failed');
const listing=cp.spawnSync(adb,['shell','-T',`run-as ${pkg} ls files/stage3-private`],{encoding:'utf8',windowsHide:true});
if(listing.status!==0)throw new Error('Cannot verify private directory cleanup');
const remaining=listing.stdout.split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
if(remaining.some(n=>names.includes(n)))throw new Error('Owned private fixture still exists');
const result={package:pkg,explicitlyNamedFilesRemoved:names.length,ownedPrivateFilesRemaining:0,otherFilesRemaining:remaining.length,uninstallOrClear:false,passed:true};
const output=path.join(repo,'docs/stage-3/evidence/device/private-fixture-cleanup.json');
if(fs.existsSync(output))throw new Error('Cleanup evidence exists; do not overwrite');
fs.writeFileSync(output,JSON.stringify(result,null,2));
console.log('Owned private PDFs, reference records and derived reports removed and absence verified.');
