import {randomUUID,createHash} from 'node:crypto';
import {localOnly,failure} from '../../../lib/access';
import {db,atomic,audit,fingerprint,ensureOpen,post} from '../../../lib/db';
import {parseFile,normalizeRow} from '../../../lib/importer';
export async function POST(req:Request){try{localOnly(req);
 if(req.headers.get('content-type')?.includes('multipart/form-data')){const form=await req.formData();const file=form.get('file') as File;if(!file||file.size>10*1024*1024)throw Error('10MB 이하의 파일을 선택하세요.');const rows=await parseFile(Buffer.from(await file.arrayBuffer()),file.name);return Response.json({rows,name:file.name});}
 const body=await req.json();
 if(body.action==='stage'){
 const {rows,map,cash_id,name}=body;if(!Array.isArray(rows)||rows.length>10001||rows.length<2)throw Error('파일 행 수를 확인하세요.');if(!db().prepare('SELECT id FROM financial_accounts WHERE id=? AND active=1').get(cash_id))throw Error('계좌를 선택하세요.');
 const normalized=rows.slice(1).map((row:string[],i:number)=>{try{const r=normalizeRow(row,map);ensureOpen(r.date);return {...r,cash_id,raw:JSON.stringify(row)};}catch(e){throw Error(`${i+2}행: ${(e as Error).message}`);}});
 const id=atomic(()=>{const id=randomUUID();db().prepare('INSERT INTO imports VALUES (?,?,?,?)').run(id,name,createHash('sha256').update(JSON.stringify(rows)).digest('hex'),new Date().toISOString());for(const r of normalized)db().prepare('INSERT INTO bank_rows VALUES (?,?,?,?,?,?,?,?,?,?,?)').run(randomUUID(),id,cash_id,r.date,r.time,r.description,r.deposit,r.withdrawal,r.balance,fingerprint(r),r.raw);audit('은행 원본 가져오기',id,null,{name,count:normalized.length});return id;});return Response.json({ok:true,id});
 }
 if(body.action==='commit'){if(!Array.isArray(body.items)||!body.items.length||body.items.length>10000)throw Error('반영할 거래를 선택하세요.');const result=atomic(()=>body.items.map((item:Parameters<typeof post>[0])=>post(item)));return Response.json({ok:true,result});}
 throw Error('잘못된 요청입니다.');
 }catch(e){return failure(e);}}
