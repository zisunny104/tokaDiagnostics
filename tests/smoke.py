from pathlib import Path
import subprocess,zipfile,shutil,datetime
root=Path(__file__).resolve().parents[1]
java=Path('C:/Users/zisun/.jdks/jbr-21.0.11/bin')
core=root.parent/'Nukkit/target/nukkit-1.0-SNAPSHOT.jar'
classes=root/'target/smoke-classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac.exe'),'-proc:none','-cp',str(core),'-d',str(classes),str(root/'tests/SmokeProbe.java')],check=True)
run=root/'target'/('smoke-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));(run/'plugins').mkdir(parents=True)
shutil.copy2(root/'output/tokaDiagnostics-0.3.0.jar',run/'plugins')
with zipfile.ZipFile(run/'plugins/SmokeProbe.jar','w') as jar:
 jar.write(classes/'SmokeProbe.class','SmokeProbe.class')
 jar.writestr('plugin.yml','name: SmokeProbe\nmain: SmokeProbe\nversion: test\napi: ["1.0.0"]\ndepend: [tokaDiagnostics]\n')
(run/'nukkit.yml').write_text('settings:\n  language: eng\n')
(run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=19148\nlevel-name=test\nlevel-type=void\nview-distance=1\nxbox-auth=off\nauto-save=off\nenable-query=off\nallow-nether=off\nallow-the-end=off\n')
with (run/'console.log').open('wb') as output:
 process=subprocess.Popen([str(java/'java.exe'),'-Xms128m','-Xmx384m','-jar',str(core)],cwd=run,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT)
 try:process.wait(timeout=90)
 except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=15);raise RuntimeError(str(run))
log=(run/'console.log').read_text(errors='replace')
assert 'DIAGNOSTICS_SMOKE_PASS' in log and 'DIAGNOSTICS_SMOKE_FAIL' not in log, str(run/'console.log')
assert 'PRELOGIN_NULL_UUID_PASS' in log, str(run/'console.log')
reports=list((run/'plugins/tokaDiagnostics/reports').glob('*.zip'));assert len(reports)==2
validated=False
for report in reports:
 with zipfile.ZipFile(report) as z:
  trace=z.read('trace.txt').decode();assert 'SMOKE' in trace and 'AUTO_START' in trace and 'LoginPacket' in trace
  assert 'format=2' in trace and '[packet-totals]' in trace and '[lifecycle] dropped=0' in trace
  assert 'crc32First4096' in trace or 'protocol=' in trace
  assert 'coreJarOnDiskSha256=' in trace and 'README.txt' in z.namelist()
  assert 'validationPendingAtExport=0' in trace
  if 'LevelChunk PASS\t1' in trace:
   assert 'BlockEntityNBT PASS\t1' in trace;validated=True
assert validated,'core-generated chunk and NBT validation did not pass'
print('DIAGNOSTICS_BOOT_EXPORT_PASS',run)
