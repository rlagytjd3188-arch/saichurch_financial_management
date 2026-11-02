import {DatabaseSync} from 'node:sqlite';
import {mkdirSync} from 'node:fs';
import path from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {automaticLines,validateLines,validDate,money,balance,descendants,type Account,type Entry,type Line} from './accounting.ts';
let instance:DatabaseSync;
export function db(){
 if(instance)return instance;
 const dir=process.env.DATA_DIR||path.join(process.cwd(),'data');mkdirSync(dir,{recursive:true,mode:0o700});
 instance=new DatabaseSync(path.join(dir,'finance.sqlite'));instance.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
 CREATE TABLE IF NOT EXISTS years(year INTEGER PRIMARY KEY,status TEXT NOT NULL CHECK(status IN ('open','preparing','closed')));
 CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY,code TEXT UNIQUE NOT NULL,name TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN ('asset','liability','equity','income','expense')),parent_id TEXT REFERENCES accounts(id),active INTEGER NOT NULL DEFAULT 1);
 CREATE TABLE IF NOT EXISTS financial_accounts(id TEXT PRIMARY KEY,name TEXT NOT NULL,institution TEXT NOT NULL,ledger_account_id TEXT UNIQUE NOT NULL REFERENCES accounts(id),active INTEGER NOT NULL DEFAULT 1);
 CREATE TABLE IF NOT EXISTS entries(id TEXT PRIMARY KEY,date TEXT NOT NULL,year INTEGER NOT NULL REFERENCES years(year),kind TEXT NOT NULL,description TEXT NOT NULL,counterparty TEXT NOT NULL,reverses_id TEXT UNIQUE REFERENCES entries(id),created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS lines(id TEXT PRIMARY KEY,entry_id TEXT NOT NULL REFERENCES entries(id),account_id TEXT NOT NULL REFERENCES accounts(id),debit TEXT NOT NULL,credit TEXT NOT NULL,CHECK((debit='0')<>(credit='0')));
 CREATE TABLE IF NOT EXISTS imports(id TEXT PRIMARY KEY,name TEXT NOT NULL,file_hash TEXT NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS bank_rows(id TEXT PRIMARY KEY,import_id TEXT NOT NULL REFERENCES imports(id),cash_id TEXT NOT NULL REFERENCES financial_accounts(id),date TEXT NOT NULL,time TEXT NOT NULL,description TEXT NOT NULL,deposit TEXT NOT NULL,withdrawal TEXT NOT NULL,balance TEXT NOT NULL,fingerprint TEXT NOT NULL,raw TEXT NOT NULL);
 CREATE INDEX IF NOT EXISTS bank_rows_fingerprint ON bank_rows(fingerprint);
 CREATE TABLE IF NOT EXISTS bank_links(row_id TEXT PRIMARY KEY REFERENCES bank_rows(id),entry_id TEXT NOT NULL REFERENCES entries(id),fingerprint TEXT UNIQUE NOT NULL);
 CREATE TABLE IF NOT EXISTS managed_sources(key TEXT PRIMARY KEY,year INTEGER NOT NULL,source_row INTEGER NOT NULL,payload TEXT NOT NULL,entry_id TEXT NOT NULL REFERENCES entries(id),UNIQUE(year,source_row));
 CREATE TABLE IF NOT EXISTS managed_openings(year INTEGER PRIMARY KEY,amount TEXT NOT NULL,cash_id TEXT NOT NULL REFERENCES financial_accounts(id),entry_id TEXT REFERENCES entries(id),date TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS managed_references(hash TEXT PRIMARY KEY,payload TEXT NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS income_targets(id TEXT PRIMARY KEY,year INTEGER NOT NULL REFERENCES years(year),account_id TEXT NOT NULL REFERENCES accounts(id),amount TEXT NOT NULL,UNIQUE(year,account_id));
 CREATE TABLE IF NOT EXISTS bank_suggestions(row_id TEXT PRIMARY KEY REFERENCES bank_rows(id),account_id TEXT NOT NULL REFERENCES accounts(id),source_note TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS budgets(id TEXT PRIMARY KEY,year INTEGER NOT NULL REFERENCES years(year),account_id TEXT NOT NULL REFERENCES accounts(id),amount TEXT NOT NULL,UNIQUE(year,account_id));
 CREATE TABLE IF NOT EXISTS rules(id TEXT PRIMARY KEY,pattern TEXT UNIQUE NOT NULL,account_id TEXT NOT NULL REFERENCES accounts(id));
 CREATE TABLE IF NOT EXISTS reconciliations(id TEXT PRIMARY KEY,cash_id TEXT NOT NULL REFERENCES financial_accounts(id),date TEXT NOT NULL,bank_balance TEXT NOT NULL,book_balance TEXT NOT NULL,created_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS audits(id TEXT PRIMARY KEY,at TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,before_json TEXT,after_json TEXT,reason TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
 CREATE TRIGGER IF NOT EXISTS entry_no_update BEFORE UPDATE ON entries BEGIN SELECT RAISE(ABORT,'반영된 분개는 변경할 수 없습니다.'); END;
 CREATE TRIGGER IF NOT EXISTS entry_no_delete BEFORE DELETE ON entries BEGIN SELECT RAISE(ABORT,'분개는 역분개로 취소하세요.'); END;
 CREATE TRIGGER IF NOT EXISTS line_no_update BEFORE UPDATE ON lines BEGIN SELECT RAISE(ABORT,'반영된 분개는 변경할 수 없습니다.'); END;
 CREATE TRIGGER IF NOT EXISTS line_no_delete BEFORE DELETE ON lines BEGIN SELECT RAISE(ABORT,'반영된 분개는 변경할 수 없습니다.'); END;
 CREATE TRIGGER IF NOT EXISTS bank_no_update BEFORE UPDATE ON bank_rows BEGIN SELECT RAISE(ABORT,'은행 원본은 변경할 수 없습니다.'); END;
 CREATE TRIGGER IF NOT EXISTS bank_no_delete BEFORE DELETE ON bank_rows BEGIN SELECT RAISE(ABORT,'은행 원본은 삭제할 수 없습니다.'); END;
 CREATE TRIGGER IF NOT EXISTS managed_source_no_update BEFORE UPDATE ON managed_sources BEGIN SELECT RAISE(ABORT,'정리장부 원본 변경 금지'); END;
 CREATE TRIGGER IF NOT EXISTS managed_source_no_delete BEFORE DELETE ON managed_sources BEGIN SELECT RAISE(ABORT,'정리장부 원본 삭제 금지'); END;
 CREATE TRIGGER IF NOT EXISTS audit_no_update BEFORE UPDATE ON audits BEGIN SELECT RAISE(ABORT,'이력 변경 금지'); END;
 CREATE TRIGGER IF NOT EXISTS audit_no_delete BEFORE DELETE ON audits BEGIN SELECT RAISE(ABORT,'이력 삭제 금지'); END;`);
 if(!instance.prepare('SELECT year FROM years LIMIT 1').get())instance.prepare("INSERT INTO years VALUES (?, 'open')").run(new Date().getFullYear());
 return instance;
}
export function atomic<T>(fn:()=>T):T{const d=db();d.exec('BEGIN IMMEDIATE');try{const result=fn();d.exec('COMMIT');return result;}catch(e){d.exec('ROLLBACK');throw e;}}
export function audit(action:string,target:string,before:unknown,after:unknown,reason=''){db().prepare('INSERT INTO audits VALUES (?,?,?,?,?,?,?)').run(randomUUID(),new Date().toISOString(),action,target,JSON.stringify(before??null),JSON.stringify(after??null),reason);}
function rows(table:string){return db().prepare(`SELECT * FROM ${table}`).all();}
export function snapshot(){const d=db();const entries=d.prepare('SELECT * FROM entries ORDER BY date DESC,created_at DESC').all().map(e=>({...e,lines:d.prepare('SELECT account_id,debit,credit FROM lines WHERE entry_id=?').all(e.id)}));const managedRefs=rows('managed_references');const imported=new Set(rows('managed_sources').map(s=>s.key));const outstanding=new Map<string,unknown>();for(const ref of managedRefs){for(const row of JSON.parse(String(ref.payload)).trackedRows||[]){if(!imported.has(row.key))outstanding.set(row.key,row);}}return {managed_pending:[...outstanding.values()],version:1,generated_at:new Date().toISOString(),accounts:rows('accounts'),financial_accounts:rows('financial_accounts'),years:rows('years'),entries,budgets:rows('budgets'),income_targets:rows('income_targets'),managed_references:managedRefs,managed_sources:rows('managed_sources'),managed_openings:rows('managed_openings'),rules:rows('rules'),imports:rows('imports'),bank_rows:d.prepare('SELECT r.*, l.entry_id, s.account_id AS suggested_account_id, s.source_note, CASE WHEN EXISTS(SELECT 1 FROM bank_links b WHERE b.fingerprint=r.fingerprint) THEN 1 ELSE 0 END AS duplicate FROM bank_rows r LEFT JOIN bank_links l ON l.row_id=r.id LEFT JOIN bank_suggestions s ON s.row_id=r.id').all(),reconciliations:rows('reconciliations'),audits:db().prepare('SELECT * FROM audits ORDER BY at DESC').all(),last_published:(d.prepare("SELECT value FROM settings WHERE key='last_published'").get()?.value??null)};}
export function ensureOpen(date:string){validDate(date);const y=db().prepare('SELECT status FROM years WHERE year=?').get(Number(date.slice(0,4)));if(y?.status!=='open')throw Error('진행중인 회계연도의 거래만 반영할 수 있습니다.');}
function account(id:string){const a=db().prepare('SELECT * FROM accounts WHERE id=? AND active=1').get(id) as Account|undefined;if(!a)throw Error('활성 계정과목을 선택하세요.');if(db().prepare('SELECT id FROM accounts WHERE parent_id=?').get(id))throw Error('하위 계정과목을 선택하세요.');return a;}
export function post(input:{date:string;kind:string;description:string;counterparty?:string;amount?:string;cash_id?:string;category_id?:string;lines?:Line[];row_id?:string;match_row_id?:string;remember?:boolean}){
 ensureOpen(input.date);if(!input.description?.trim())throw Error('적요를 입력하세요.');
 let lines:Line[];
 if(input.kind==='opening'||input.kind==='journal'){lines=input.lines??[];}
 else{
 if(!['income','expense','transfer','liability','payment'].includes(input.kind))throw Error('알 수 없는 거래 종류입니다.');
 const cash=input.kind==='liability'?{ledger_account_id:input.cash_id}:db().prepare('SELECT ledger_account_id FROM financial_accounts WHERE id=? AND active=1').get(input.cash_id??'');if(!cash)throw Error('자금 계좌를 선택하세요.');
 let target=input.category_id??'';
 if(input.kind==='transfer'){const other=db().prepare('SELECT ledger_account_id FROM financial_accounts WHERE id=? AND active=1').get(target);if(!other)throw Error('받는 계좌를 선택하세요.');target=String(other.ledger_account_id);}
 const category=account(target);const expected=({income:'income',expense:'expense',transfer:'asset',liability:'expense',payment:'liability'} as const)[input.kind as 'income'];
 if(category.type!==expected)throw Error('거래 종류에 맞는 분류를 선택하세요.');
 if(input.kind==='liability'){const liability=account(input.cash_id??'');if(liability.type!=='liability')throw Error('부채 계정을 선택하세요.');}
 lines=automaticLines(input.kind,input.amount??'',String(cash.ledger_account_id),target);
 }
 validateLines(lines);for(const l of lines){const a=account(l.account_id);if(input.kind==='opening'&&['income','expense'].includes(a.type))throw Error('개시분개에는 자산·부채·순자산만 사용할 수 있습니다.');}
 const id=randomUUID();db().prepare('INSERT INTO entries VALUES (?,?,?,?,?,?,?,?)').run(id,input.date,Number(input.date.slice(0,4)),input.kind,input.description,input.counterparty??'',null,new Date().toISOString());
 for(const l of lines)db().prepare('INSERT INTO lines VALUES (?,?,?,?,?)').run(randomUUID(),id,l.account_id,money(l.debit).toString(),money(l.credit).toString());
 for(const rowId of [input.row_id,input.match_row_id].filter(Boolean)){
 const r=db().prepare('SELECT * FROM bank_rows WHERE id=?').get(rowId!);if(!r)throw Error('은행 원본을 찾지 못했습니다.');
 const cash=db().prepare('SELECT ledger_account_id FROM financial_accounts WHERE id=?').get(r.cash_id);
 const delta=lines.filter(l=>l.account_id===cash?.ledger_account_id).reduce((s,l)=>s+BigInt(l.debit)-BigInt(l.credit),0n);
 if(delta!==BigInt(String(r.deposit))-BigInt(String(r.withdrawal))||r.date!==input.date)throw Error('은행 원본의 날짜·금액·계좌와 분개가 일치하지 않습니다.');
 db().prepare('INSERT INTO bank_links VALUES (?,?,?)').run(rowId!,id,r.fingerprint);
 }
 if(input.remember&&input.category_id&&['income','expense'].includes(input.kind))db().prepare('INSERT INTO rules VALUES (?,?,?) ON CONFLICT(pattern) DO UPDATE SET account_id=excluded.account_id').run(randomUUID(),input.description,input.category_id);
 audit('거래 반영',id,null,{...input,lines});return id;
}
export function mutate(action:string,p:Record<string,any>){return atomic(()=>{
 const d=db();
 if(action==='post')return post(p as Parameters<typeof post>[0]);
 if(action==='reverse'){
 const e=d.prepare('SELECT * FROM entries WHERE id=?').get(p.id);if(!e)throw Error('거래를 찾지 못했습니다.');if(!p.reason?.trim())throw Error('취소 사유를 입력하세요.');ensureOpen(p.date);ensureOpen(String(e.date));if(p.date.slice(0,4)!==String(e.date).slice(0,4))throw Error('원거래와 같은 연도에 취소하세요.');
 if(e.reverses_id||d.prepare('SELECT id FROM entries WHERE reverses_id=?').get(p.id))throw Error('이미 취소되었거나 역분개인 거래입니다.');
 const id=randomUUID();d.prepare('INSERT INTO entries VALUES (?,?,?,?,?,?,?,?)').run(id,p.date,Number(p.date.slice(0,4)),'reversal',`취소: ${e.description}`,e.counterparty,p.id,new Date().toISOString());
 for(const l of d.prepare('SELECT * FROM lines WHERE entry_id=?').all(p.id))d.prepare('INSERT INTO lines VALUES (?,?,?,?,?)').run(randomUUID(),id,l.account_id,l.credit,l.debit);audit('거래 취소',p.id,e,{id},p.reason);return id;
 }
 if(action==='year'){if(!Number.isInteger(Number(p.year))||Number(p.year)<2000||Number(p.year)>2199)throw Error('회계연도를 확인하세요.');d.prepare("INSERT INTO years VALUES (?, 'open')").run(Number(p.year));audit('회계연도 추가',p.year,null,p);}
 else if(action==='account'){
 if(!['asset','liability','equity','income','expense'].includes(p.type)||!p.name?.trim()||!p.code?.trim())throw Error('계정 코드·이름·유형을 확인하세요.');
 if(p.parent_id){const parent=d.prepare('SELECT type FROM accounts WHERE id=?').get(p.parent_id);if(parent?.type!==p.type)throw Error('상위 계정과 유형이 같아야 합니다.');if(d.prepare('SELECT id FROM lines WHERE account_id=? LIMIT 1').get(p.parent_id))throw Error('분개 이력이 있는 계정 아래에는 하위 계정을 추가할 수 없습니다.');}
 const id=randomUUID();d.prepare('INSERT INTO accounts VALUES (?,?,?,?,?,1)').run(id,p.code,p.name,p.type,p.parent_id||null);audit('계정과목 추가',id,null,p);
 }
 else if(action==='deactivate'){const before=d.prepare('SELECT * FROM accounts WHERE id=?').get(p.id);if(d.prepare('SELECT id FROM financial_accounts WHERE ledger_account_id=? AND active=1').get(p.id))throw Error('사용중인 자금 계좌에 연결되어 있습니다.');d.prepare('UPDATE accounts SET active=0 WHERE id=?').run(p.id);audit('계정 비활성화',p.id,before,{active:0});}
 else if(action==='cash'){
 if(!p.name?.trim())throw Error('계좌명을 입력하세요.');const a=account(p.ledger_account_id);if(a.type!=='asset')throw Error('자산 계정을 연결하세요.');const id=randomUUID();d.prepare('INSERT INTO financial_accounts VALUES (?,?,?,?,1)').run(id,p.name,p.institution||'',p.ledger_account_id);audit('자금 계좌 추가',id,null,p);
 }
 else if(action==='budget'){
 ensureOpen(`${p.year}-01-01`);money(p.amount);const a=d.prepare('SELECT type FROM accounts WHERE id=?').get(p.account_id);if(a?.type!=='expense')throw Error('비용 계정을 선택하세요.');
 const all=rows('accounts') as Account[];for(const b of d.prepare('SELECT * FROM budgets WHERE year=? AND account_id<>?').all(Number(p.year),p.account_id)){if(descendants(all,String(b.account_id)).includes(p.account_id)||descendants(all,p.account_id).includes(String(b.account_id)))throw Error('상위·하위 항목 예산을 중복 설정할 수 없습니다.');}
 const before=d.prepare('SELECT * FROM budgets WHERE year=? AND account_id=?').get(Number(p.year),p.account_id);d.prepare('INSERT INTO budgets VALUES (?,?,?,?) ON CONFLICT(year,account_id) DO UPDATE SET amount=excluded.amount').run(randomUUID(),Number(p.year),p.account_id,money(p.amount).toString());audit('예산 변경',p.account_id,before,p);
 }
 else if(action==='rule'){account(p.account_id);if(!p.pattern?.trim())throw Error('검색어를 입력하세요.');d.prepare('INSERT INTO rules VALUES (?,?,?) ON CONFLICT(pattern) DO UPDATE SET account_id=excluded.account_id').run(randomUUID(),p.pattern,p.account_id);audit('추천 규칙 저장',p.pattern,null,p);}
 else if(action==='reconcile'){
 validDate(p.date);const cash=d.prepare('SELECT * FROM financial_accounts WHERE id=?').get(p.cash_id);if(!cash)throw Error('계좌를 선택하세요.');const book=balance(snapshot().entries as unknown as Entry[],String(cash.ledger_account_id),p.date).toString();money(p.bank_balance);const id=randomUUID();d.prepare('INSERT INTO reconciliations VALUES (?,?,?,?,?,?,?)').run(id,p.cash_id,p.date,money(p.bank_balance).toString(),book,new Date().toISOString());audit('잔액 대사',id,null,p);
 }
 else throw Error('지원하지 않는 작업입니다.');
 });}
export function fingerprint(r:{cash_id:string;date:string;time:string;description:string;deposit:string;withdrawal:string;balance:string}){return createHash('sha256').update(JSON.stringify([r.cash_id,r.date,r.time,r.description.trim(),r.deposit,r.withdrawal,r.balance])).digest('hex');}
