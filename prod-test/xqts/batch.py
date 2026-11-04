import subprocess,sys,re,os,shutil,glob
tree,S,O=sys.argv[1],sys.argv[2],sys.argv[3]
X='RangeExpr-411d,RangeExpr-409d,RangeExpr-408d,RangeExpr-409c,RangeExpr-408c,GenCompEq-21'
cat=open(f'{S}/xqts/cache/QT4/qt4tests-master/catalog.xml').read()
sets=re.findall(r'<test-set\s[^>]*name="([^"]+)"',cat)
out=f'{O}/{tree}-QT4'; shutil.rmtree(out,ignore_errors=True)
run=f'{O}/run-{tree}-QT4'; os.makedirs(run,exist_ok=True)
env=dict(os.environ,JAVA_OPTS='-XX:+UseZGC -Xmx8g -XX:+ExitOnOutOfMemoryError')
R=f'{S}/wt-{tree}/exist-xqts/target/exist-xqts-dir/bin/exist-xqts-runner.sh'
def go(b,t):
    with open(f'{O}/{tree}-QT4.log','a') as l:
        try:
            return subprocess.run([R,'--xqts-version','QT4','-l',f'{S}/xqts/cache/QT4','--exclude-test-case',X,'--test-set',','.join(b),'--output-dir',out],cwd=run,env=env,stdout=l,stderr=l,timeout=t).returncode
        except subprocess.TimeoutExpired:
            subprocess.run(['pkill','-f',f'wt-{tree}/exist-xqts'])
            return 'timeout'
failed=[]
def missing(b): return [s for s in b if not glob.glob(f'{out}/junit/data/TEST-{s}.xml')]
for i in range(0,len(sets),40):
    b=sets[i:i+40]; rc=go(b,600); miss=missing(b)
    print(f'batch {i} rc={rc} missing={len(miss)}',flush=True)
    if rc!=0 or miss:
        for s in (miss or b):
            rc2=go([s],400)
            if rc2!=0 or missing([s]): failed.append((s,rc2)); print(' FAILED',s,rc2,flush=True)
print('sets',len(sets),'failed',failed,flush=True)
