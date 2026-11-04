import xml.etree.ElementTree as ET,glob,os,sys,json,collections
O=sys.argv[1]
def load(d):
    r={}
    for f in glob.glob(d+'/junit/data/TEST-*.xml'):
        try: root=ET.parse(f).getroot()
        except Exception as e: print('BAD',f,e); continue
        for tc in root.iter('testcase'):
            st='pass'; msg=''
            for c in tc:
                if c.tag in('failure','error','skipped'):
                    st=c.tag; msg=(c.get('message') or c.text or '')[:300]
            r[(tc.get('classname'),tc.get('name'))]=(st,msg)
    return r
out={}
for v in ('HEAD','QT4'):
    b=load(f'{O}/base-{v}'); a=load(f'{O}/feat-{v}')
    print(v,'base',len(b),collections.Counter(x[0] for x in b.values()))
    print(v,'feat',len(a),collections.Counter(x[0] for x in a.values()))
    common=set(a)&set(b)
    reg=sorted(k for k in common if b[k][0]=='pass' and a[k][0]!='pass')
    imp=sorted(k for k in common if b[k][0]!='pass' and a[k][0]=='pass')
    chg=sorted(k for k in common if b[k][0]!='pass' and a[k][0]!='pass' and b[k][0]!=a[k][0])
    print(' sets base/feat',len(glob.glob(f'{O}/base-{v}/junit/data/TEST-*.xml')),len(glob.glob(f'{O}/feat-{v}/junit/data/TEST-*.xml')),' only-base',len(set(b)-set(a)),'only-feat',len(set(a)-set(b)))
    print(' newly failing',len(reg),'newly passing',len(imp),'fail->error kind change',len(chg))
    out[v]={'reg':[(k,a[k]) for k in reg],'imp':[(k,b[k]) for k in imp],'onlyb':[k for k in set(b)-set(a)],'onlya':[k for k in set(a)-set(b)]}
    fam=collections.Counter(k[0] for k in reg); print(' reg by set',fam.most_common(30))
    fam=collections.Counter(k[0] for k in imp); print(' imp by set',fam.most_common(15))
    print(' only-base sets',collections.Counter(k[0] for k in set(b)-set(a)).most_common(8))
    print(' only-feat sets',collections.Counter(k[0] for k in set(a)-set(b)).most_common(8))
json.dump(out,open(O+'/cmp.json','w'),default=str,indent=0)
