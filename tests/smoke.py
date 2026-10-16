from pathlib import Path
import subprocess,zipfile,shutil,datetime
root=Path(__file__).resolve().parents[1]
java=Path('C:/Users/zisun/.jdks/jbr-21.0.11/bin')
core=root.parent/'Nukkit/target/nukkit-1.0-SNAPSHOT.jar'
classes=root/'target/smoke-classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'javac.exe'),'-proc:none','-cp',str(core),'-d',str(classes),str(root/'tests/SmokeProbe.java')],check=True)
run=root/'target'/('smoke-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));(run/'plugins').mkdir(parents=True)
shutil.copy2(root/'output/tokaDiagnostics-0.1.0.jar',run/'plugins')
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
reports=list((run/'plugins/tokaDiagnostics/reports').glob('*.zip'));assert len(reports)==1
with zipfile.ZipFile(reports[0]) as z: assert 'SMOKE' in z.read('trace.txt').decode()
print('DIAGNOSTICS_BOOT_EXPORT_PASS',run)
