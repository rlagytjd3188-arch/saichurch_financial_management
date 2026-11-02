import {randomUUID} from 'node:crypto';
import {db,atomic,audit,post,fingerprint} from './db.ts';
import {yearSummary,type WorkbookPlan} from './church-workbook.ts';
export type MigrationOptions={year:number;cashMappings:Record<string,string>;opening:boolean;guide:boolean;budgetMappings:Record<string,string>};
export function importWorkbook(plan:WorkbookPlan,options:MigrationOptions){return atomic(()=>{
 const d=db(),year=Number(options.year);if(!Number.isInteger(year)||!plan.rows.some(r=>r.date.startsWith(`${year}-`)))throw Error('파일에 존재하는 연도를 선택하세요.');
 if(plan.errors.length)throw Error('파일 오류 행을 수정한 후 다시 업로드하세요.');
 const summaries=yearSummary(plan,year).filter(s=>s.count);const yearStatus=d.prepare('SELECT status FROM years WHERE year=?').get(year);if(yearStatus&&yearStatus.status!=='open')throw Error('마감된 회계연도입니다.');if(!yearStatus)d.prepare("INSERT INTO years VALUES (?,'open')").run(year);
 if(options.guide)for(const a of plan.guide){const old=d.prepare('SELECT * FROM accounts WHERE code=?').get(a.code);if(old){if(old.name!==a.name||old.type!==a.type||old.active!==1)throw Error(`계정 코드 ${a.code}가 기존 설정과 다릅니다. 계정 가이드 가져오기를 해제하고 기존 설정을 확인하세요.`);}else{const id=randomUUID();d.prepare('INSERT INTO accounts VALUES (?,?,?,?,NULL,1)').run(id,a.code,a.name,a.type);audit('기존 계정 가이드 등록',id,null,a);}}
 const cashes=new Map<string,{id:string;ledger_account_id:string}>();
 for(const s of summaries){let cash;
 const selected=options.cashMappings[s.sheet];if(selected&&selected!=='new'){cash=d.prepare('SELECT id,ledger_account_id FROM financial_accounts WHERE id=? AND active=1').get(selected);if(!cash)throw Error('연결할 활성 계좌를 선택하세요.');}
 else if(selected==='new'){
 if(d.prepare('SELECT id FROM financial_accounts WHERE name=?').get(s.sheet))throw Error(`${s.sheet}: 같은 이름의 계좌가 있습니다. 기존 계좌를 연결하세요.`);
 const aid=randomUUID(),id=randomUUID();d.prepare('INSERT INTO accounts VALUES (?,?,?,?,NULL,1)').run(aid,`SAI-${aid.slice(0,8)}`,s.sheet,'asset');d.prepare('INSERT INTO financial_accounts VALUES (?,?,?,?,1)').run(id,s.sheet,'',aid);cash={id,ledger_account_id:aid};audit('기존 통장 계좌 등록',id,null,{name:s.sheet});
 }else throw Error(`${s.sheet}: 연결할 계좌를 선택하세요.`);
 if([...cashes.values()].some(c=>c.id===cash.id))throw Error('서로 다른 통장 시트를 같은 계좌에 연결할 수 없습니다.');cashes.set(s.sheet,cash as {id:string;ledger_account_id:string});
 }
 if(options.opening){
 if(summaries.some(s=>s.breaks))throw Error('잔액 연속성이 맞지 않는 시트가 있어 기초잔액을 자동 등록할 수 없습니다. 기초잔액 옵션을 해제하고 원본을 확인하세요.');
 // Opening is only permitted on a clean year. This prevents duplicate balances on reimport.
 if(d.prepare('SELECT id FROM entries WHERE year=? LIMIT 1').get(year)||d.prepare('SELECT id FROM bank_rows WHERE date LIKE ? LIMIT 1').get(`${year}-%`))throw Error('이미 거래 또는 은행 원본이 있는 연도입니다. 기초잔액 옵션을 해제하고 다시 가져오세요.');
 let eq=d.prepare("SELECT * FROM accounts WHERE code='SAI-OPEN'").get();if(!eq){const id=randomUUID();d.prepare("INSERT INTO accounts VALUES (?,'SAI-OPEN','기초 이월순자산','equity',NULL,1)").run(id);eq={id,type:'equity',active:1};}
 if(eq.type!=='equity'||eq.active!==1)throw Error('기초 이월순자산 계정을 확인하세요.');
 for(const s of summaries){const n=BigInt(s.opening);if(n===0n)continue;const a=cashes.get(s.sheet)!;post({date:`${year}-01-01`,kind:'opening',description:`${s.sheet} ${year}년 기초잔액 (파일 첫 잔액 기준)`,lines:[{account_id:a.ledger_account_id,debit:n>0n?n.toString():'0',credit:n<0n?(-n).toString():'0'},{account_id:String(eq.id),debit:n<0n?(-n).toString():'0',credit:n>0n?n.toString():'0'}]});}
 }
 const iid=randomUUID();d.prepare('INSERT INTO imports VALUES (?,?,?,?)').run(iid,`기존 교회 장부 ${year}`,plan.hash,new Date().toISOString());let added=0,duplicates=0;
 const seen=new Set<string>();
 for(const row of plan.rows.filter(r=>r.date.startsWith(`${year}-`))){const cash=cashes.get(row.sheet)!;const fp=fingerprint({...row,cash_id:cash.id});if(seen.has(fp)||d.prepare('SELECT id FROM bank_rows WHERE fingerprint=?').get(fp)){duplicates++;continue;}seen.add(fp);const rowId=randomUUID();d.prepare('INSERT INTO bank_rows VALUES (?,?,?,?,?,?,?,?,?,?,?)').run(rowId,iid,cash.id,row.date,row.time,row.description,row.deposit,row.withdrawal,row.balance,fp,JSON.stringify({sheet:row.sheet,row:row.row,values:row.raw,memo:row.memo}));if(row.suggested_code){const g=plan.guide.find(g=>g.code===row.suggested_code);const a=d.prepare('SELECT id FROM accounts WHERE code=? AND name=? AND type=? AND active=1').get(g!.code,g!.name,g!.type);if(a)d.prepare('INSERT INTO bank_suggestions VALUES (?,?,?)').run(rowId,a.id,'기존 내역의 날짜·금액 일치 후보 · 확인 필요');}added++;}
 if(Object.values(options.budgetMappings||{}).some(Boolean)){
 if(plan.budgetYear!==year)throw Error('예산은 파일에 명시된 연도에만 가져올 수 있습니다.');
 const sums=new Map<string,bigint>();for(const b of plan.budgets){let target=options.budgetMappings[b.key];if(!target)continue;if(target.startsWith('code:')){const a=d.prepare('SELECT id FROM accounts WHERE code=?').get(target.slice(5));if(!a)throw Error('예산 연결 계정을 먼저 등록하세요.');target=String(a.id);}sums.set(target,(sums.get(target)||0n)+BigInt(b.amount));}
 // Existing budgets are not silently replaced. All selected budgets are applied atomically.
 for(const [account_id,amount] of sums){if(d.prepare('SELECT id FROM budgets WHERE year=? AND account_id=?').get(year,account_id))throw Error('이미 예산이 있는 항목입니다. 예산 가져오기를 해제하고 예산 메뉴에서 개정하세요.');
 const a=d.prepare("SELECT * FROM accounts WHERE id=? AND type='expense' AND active=1").get(account_id);if(!a)throw Error('예산에 연결할 활성 비용 계정을 선택하세요.');
 const all=d.prepare('SELECT id,parent_id FROM accounts').all();function family(id:string):string[]{return [id,...all.filter(a=>a.parent_id===id).flatMap(a=>family(String(a.id)))];}
 for(const old of d.prepare('SELECT account_id FROM budgets WHERE year=?').all(year)){if(family(String(old.account_id)).includes(account_id)||family(account_id).includes(String(old.account_id)))throw Error('상위·하위 예산 중복입니다. 연결 항목을 확인하세요.');}
 d.prepare('INSERT INTO budgets VALUES (?,?,?,?)').run(randomUUID(),year,account_id,amount.toString());audit('기존 예산 가져오기',account_id,null,{year,amount:amount.toString()});}
 }
 audit('기존 교회 장부 가져오기',iid,null,{year,hash:plan.hash,added,duplicates,opening:options.opening});return {added,duplicates,year};
});}
