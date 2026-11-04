from pathlib import Path
import tempfile, sqlite3, subprocess, os, sys, json

exporter = Path(__file__).resolve().parents[1] / 'tools/export_from_termux.py'
with tempfile.TemporaryDirectory() as d:
    home = Path(d) / 'home'; home.mkdir()
    old = home / 'mobile-ai'; (old/'data').mkdir(parents=True)
    db = sqlite3.connect(old/'data/memory.db')
    db.execute('CREATE TABLE memory(id INTEGER PRIMARY KEY,ts REAL,kind TEXT,content TEXT,tags TEXT)')
    db.execute('INSERT INTO memory VALUES(1,1,"memory","โปรดตอบภาษาไทย","language")')
    db.execute('CREATE TABLE messages(id INTEGER PRIMARY KEY,ts REAL,role TEXT,content TEXT,model TEXT)')
    db.execute('INSERT INTO messages VALUES(1,2,"user","สวัสดี","")')
    db.commit(); db.close()
    (old/'config.json').write_text(json.dumps({'ai_name':'Test AI','password':'NEVER_EXPORT','api_key':'NEVER_EXPORT','language_mode':'auto'}))
    mdir = home/'.ollama/models/manifests/registry.ollama.ai/library/gemma3'; mdir.mkdir(parents=True)
    blobdir=home/'.ollama/models/blobs';blobdir.mkdir(parents=True)
    (mdir/'1b').write_text(json.dumps({'layers':[{'mediaType':'application/vnd.ollama.image.model','digest':'sha256:'+'a'*64}]}))
    blob=blobdir/('sha256-'+'a'*64)
    blob.write_bytes(b'GGUF'+b'fake-data'*300)
    dest=Path(d)/'output'
    env={**os.environ,'HOME':str(home),'LOCAL_AI_EXPORT_DEST':str(dest)}
    process=subprocess.run([sys.executable,str(exporter),'gemma3:1b'],env=env,capture_output=True,text=True)
    assert process.returncode == 0,process.stderr
    assert (dest/'memory.db').is_file() and (dest/'gemma3-1b.gguf').read_bytes()==blob.read_bytes()
    cfg=json.loads((dest/'safe-config.json').read_text())
    assert cfg=={'ai_name':'Test AI','language_mode':'auto'}
    assert (old/'data/memory.db').exists() and blob.exists()
    d2=sqlite3.connect(dest/'memory.db')
    assert d2.execute('SELECT COUNT(*) FROM memory').fetchone()[0]==1
    assert d2.execute('SELECT COUNT(*) FROM messages').fetchone()[0]==1
    d2.close()
    print('SAFE_MIGRATION_EXPORT_TEST_PASS')