#!/usr/bin/env python3
"""Private last-known-good helper snapshots. Never includes sign-in/configuration files."""
import fcntl,json,os,shutil,subprocess,sys,tempfile,time,hashlib
from pathlib import Path
ROOT=Path(os.environ.get('HATCHLYBOX_HELPER_BACKUPS',str(Path.home()/'.local/share/hatchlybox-helper-backups')))
TOOLS={'claude','codex','opencode'}
def size(path):
 if path.is_symlink():return 0
 if path.is_file():return path.stat().st_size
 return sum(size(p) for p in path.iterdir())
def snapshot(tool):
 path=shutil.which(tool)
 if not path:return
 path=Path(path); real=path.resolve()
 root=ROOT/tool;root.mkdir(parents=True,exist_ok=True)
 stage=Path(tempfile.mkdtemp(prefix='staging-',dir=root))
 try:
  sources=[];module_root=None
  if tool=='codex':
   result=subprocess.run(['npm','root','-g'],capture_output=True,text=True,timeout=20)
   if result.returncode:raise RuntimeError('Cannot locate the current Codex package; update deferred')
   module_root=Path(result.stdout.strip())/'@openai'
   sources=[p for p in module_root.glob('codex*') if p.is_dir() and '.before-restore' not in p.name and '.restore-' not in p.name]
   if not sources:raise RuntimeError('Cannot snapshot the current Codex package; update deferred')
  identity=[str(real),str(real.stat().st_mtime_ns),str(real.stat().st_size)]
  for source in sources:
   package=source/'package.json'
   if package.is_file():identity.append(package.read_text())
  fingerprint=hashlib.sha256('\n'.join(identity).encode()).hexdigest()
  old=root/'current'/'manifest.json'
  if old.is_file() and json.loads(old.read_text()).get('fingerprint')==fingerprint:return
  needed=sum(size(p) for p in sources) if sources else real.stat().st_size
  if shutil.disk_usage(root).free < needed+256*1024*1024:raise RuntimeError('Free storage before updating helpers; current versions were kept')
  if sources:
   (stage/'modules').mkdir()
   for source in sources:shutil.copytree(source,stage/'modules'/source.name,symlinks=True)
  else:shutil.copy2(real,stage/'executable')
  data={'tool':tool,'path':str(path),'real':str(real),'link':os.readlink(path) if path.is_symlink() else None,'moduleRoot':str(module_root) if module_root else None,'at':time.time(),'fingerprint':fingerprint}
  (stage/'manifest.json').write_text(json.dumps(data))
  previous=root/'previous'
  if previous.exists():shutil.rmtree(previous)
  if (root/'current').exists():(root/'current').rename(previous)
  stage.rename(root/'current')
 finally:
  if stage.exists():shutil.rmtree(stage)
def restore(tool):
 root=ROOT/tool/'current';data=json.loads((root/'manifest.json').read_text());path=Path(data['path'])
 if data.get('moduleRoot'):
  target=Path(data['moduleRoot']);target.mkdir(parents=True,exist_ok=True)
  for source in (root/'modules').iterdir():
   staged=target/(source.name+'.restore-'+str(os.getpid()))
   shutil.copytree(source,staged,symlinks=True)
   failed=target/(source.name+'.before-restore')
   if failed.exists():shutil.rmtree(failed)
   if (target/source.name).exists():(target/source.name).rename(failed)
   staged.rename(target/source.name)
 else:
  real=Path(data['real']);real.parent.mkdir(parents=True,exist_ok=True)
  temp=real.with_name(real.name+'.restore-'+str(os.getpid()));shutil.copy2(root/'executable',temp);os.replace(temp,real)
 if data.get('link'):
  temp=path.with_name(path.name+'.link-'+str(os.getpid()));temp.unlink(missing_ok=True);temp.symlink_to(data['link']);os.replace(temp,path)
 result=subprocess.run([str(path),'--version'],capture_output=True,text=True,timeout=20)
 if result.returncode:raise RuntimeError('Restored files, but the helper health check failed. Open the task console.')
 print('Restored '+tool+': '+result.stdout.strip())
if __name__=='__main__':
 try:
  command,tool=sys.argv[1:3]
  if tool not in TOOLS or command not in {'snapshot','restore'}:raise ValueError('Choose a supported helper and snapshot or restore')
  ROOT.mkdir(parents=True,exist_ok=True)
  with (ROOT/'.lock').open('w') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
   (snapshot if command=='snapshot' else restore)(tool)
 except Exception as error:
  print('Helper backup: '+str(error),file=sys.stderr);sys.exit(1)
