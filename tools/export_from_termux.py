#!/usr/bin/env python3
"""Safe, read-only migration export. Does not modify the original Termux AI."""
from pathlib import Path
import json, sqlite3, sys, shutil, os

ROOT = Path.home() / 'mobile-ai'
DEST = Path(os.environ.get('LOCAL_AI_EXPORT_DEST', '/sdcard/Download/LocalAI-Migration'))
MODELS = Path.home() / '.ollama/models'


def export():
    if not ROOT.is_dir():
        raise RuntimeError(f'Old project not found: {ROOT}')
    DEST.mkdir(parents=True, exist_ok=True)
    db = ROOT / 'data/memory.db'
    if db.is_file():
        backup = DEST / 'memory.db'
        source = sqlite3.connect(f'file:{db}?mode=ro', uri=True)
        target = sqlite3.connect(str(backup))
        try:
            source.backup(target)
        finally:
            target.close(); source.close()
        print('SAFE COPY: SQLite memory.db backed up to', backup)
    else:
        print('No legacy memory.db found; skipping')

    cfg = ROOT / 'config.json'
    if cfg.is_file():
        data = json.loads(cfg.read_text(encoding='utf-8'))
        allowed = {k:data[k] for k in ('ai_name','language_mode','memory_enabled') if k in data}
        output = DEST / 'safe-config.json'
        output.write_text(json.dumps(allowed,ensure_ascii=False,indent=2),encoding='utf-8')
        print('SAFE COPY: filtered config (no tokens, passwords or remote URLs)')

    if len(sys.argv) < 2:
        print('Model not copied. To export e.g. qwen2.5:3b, run: python export_from_termux.py qwen2.5:3b')
        return
    spec = sys.argv[1].strip()
    if ':' not in spec:
        raise ValueError('Specify model:tag, e.g. gemma3:1b')
    model, tag = spec.rsplit(':',1)
    if not model or '/' in model or '..' in model or '/' in tag or '..' in tag:
        raise ValueError('Only simple Ollama library names are supported; do not use custom model paths')
    manifest = MODELS/'manifests/registry.ollama.ai/library'/model/tag
    if not manifest.is_file():
        raise FileNotFoundError(f'Ollama manifest not found: {manifest}')
    layers = json.loads(manifest.read_text()).get('layers',[])
    found = [x for x in layers if 'model' in x.get('mediaType','')]
    if not found:
        raise RuntimeError('No model weight layer found in manifest')
    digest = found[0].get('digest','')
    if not digest.startswith('sha256:'):
        raise RuntimeError('Unexpected Ollama blob digest')
    blob = MODELS/'blobs'/('sha256-'+digest.split(':',1)[1])
    if not blob.is_file(): raise FileNotFoundError(blob)
    with blob.open('rb') as f:
        if f.read(4) != b'GGUF': raise ValueError('Ollama weight layer is not raw GGUF; no export performed')
    free = shutil.disk_usage(DEST).free
    if free < blob.stat().st_size + 512 * 1024 * 1024:
        raise RuntimeError('Not enough shared storage to safely make another model copy')
    filename = f'{model.replace("/","-")}-{tag}.gguf'
    target = DEST / filename
    if target.exists() and target.stat().st_size == blob.stat().st_size:
        print('Model copy already exists:', target)
        return
    temp = DEST / (filename+'.partial')
    with blob.open('rb') as f, temp.open('wb') as out:
        shutil.copyfileobj(f,out,length=1024*1024)
        out.flush(); os.fsync(out.fileno())
    if temp.stat().st_size != blob.stat().st_size:
        raise IOError('Model copy incomplete; original is untouched')
    temp.replace(target)
    print('SAFE COPY: GGUF model:',target,'size',target.stat().st_size)

if __name__=='__main__':
    try: export()
    except Exception as e:
        print('EXPORT FAILED:',str(e),file=sys.stderr)
        sys.exit(1)