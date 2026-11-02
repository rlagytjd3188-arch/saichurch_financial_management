import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
process.env.DATA_DIR=mkdtempSync(path.join(tmpdir(),'church-test-'));
const {db,mutate,snapshot,atomic,post,fingerprint}=await import('../lib/db.ts');
const {balance,totals,balanceSheet,budgetExecution,validateLines,suggest,money}=await import('../lib/accounting.ts');
const {normalizeRow,parseFile}=await import('../lib/importer.ts');
import type {Account,Entry} from '../lib/accounting.ts';
const year=new Date().getFullYear(),date=`${year}-01-05`;
function account(code:string,name:string,type:string){mutate('account',{code,name,type});return String(db().prepare('SELECT id FROM accounts WHERE code=?').get(code)!.id);}
const bank=account('101','운영예금','asset'),savings=account('102','정기예금','asset'),income=account('401','약정헌금','income'),expense=account('501','전기료','expense'),liability=account('201','미지급금','liability'),equity=account('301','이월순자산','equity');
mutate('cash',{name:'운영계좌',institution:'테스트',ledger_account_id:bank});mutate('cash',{name:'정기예금',ledger_account_id:savings});
const cash=String(db().prepare('SELECT id FROM financial_accounts WHERE ledger_account_id=?').get(bank)!.id),other=String(db().prepare('SELECT id FROM financial_accounts WHERE ledger_account_id=?').get(savings)!.id);
const entries=()=>snapshot().entries as unknown as Entry[],accounts=()=>snapshot().accounts as Account[];
function invariant(){for(const e of entries())validateLines(e.lines);assert.equal(balanceSheet(entries(),accounts()).difference,0n);}
function transaction(kind:string,amount:string,category_id:string,cash_id=cash){const id=mutate('post',{date,kind,amount,category_id,cash_id,description:'테스트'});invariant();return id;}
test('기초잔액은 개시분개이며 수익이 아니다',()=>{mutate('post',{date:`${year}-01-01`,kind:'opening',description:'기초잔액',lines:[{account_id:bank,debit:'2000000',credit:'0'},{account_id:equity,debit:'0',credit:'2000000'}]});assert.equal(balance(entries(),bank),2000000n);assert.equal(totals(entries(),accounts()).income,0n);invariant();});
test('헌금 100,000원 자동분개',()=>{transaction('income','100000',income);assert.equal(totals(entries(),accounts()).income,100000n);assert.equal(balance(entries(),bank),2100000n);});
test('전기료 50,000원 지출',()=>{transaction('expense','50000',expense);assert.equal(totals(entries(),accounts()).expense,50000n);});
test('계좌 간 1,000,000원 이체는 수익·비용에서 제외',()=>{const before=totals(entries(),accounts());transaction('transfer','1000000',other);assert.deepEqual(totals(entries(),accounts()),before);assert.equal(balance(entries(),savings),1000000n);});
test('같은 은행 원본의 이중 반영은 원자적으로 실패',()=>{
 const r={cash_id:cash,date,time:'12:00:00',description:'김성도',deposit:'100000',withdrawal:'0',balance:'1150000'},fp=fingerprint(r);
 db().prepare('INSERT INTO imports VALUES (?,?,?,?)').run('import','test.csv','hash',new Date().toISOString());
 for(const id of ['row1','row2'])db().prepare('INSERT INTO bank_rows VALUES (?,?,?,?,?,?,?,?,?,?,?)').run(id,'import',cash,r.date,r.time,r.description,r.deposit,r.withdrawal,r.balance,fp,'{}');
 mutate('post',{...r,kind:'income',amount:r.deposit,category_id:income,row_id:'row1'});const count=entries().length;
 assert.throws(()=>mutate('post',{...r,kind:'income',amount:r.deposit,category_id:income,row_id:'row2'}));assert.equal(entries().length,count);invariant();
});
test('분류 추천은 분개를 만들지 않는다',()=>{mutate('rule',{pattern:'한국전력',account_id:expense});const count=entries().length;assert.equal(suggest('한국전력 자동납부',snapshot().rules as any),expense);assert.equal(entries().length,count);invariant();});
test('예산 1,000,000원 중 800,000원 집행은 주의',()=>{transaction('expense','750000',expense);const x=budgetExecution(entries(),accounts(),expense,'1000000');assert.equal(x.actual,800000n);assert.equal(x.status,'warning');});
test('예산 초과는 잔여가 음수',()=>{transaction('expense','300000',expense);const x=budgetExecution(entries(),accounts(),expense,'1000000');assert.equal(x.status,'over');assert.equal(x.remaining,-100000n);});
test('미지급금 발생과 지급 시 비용은 한 번만 집계',()=>{const before=totals(entries(),accounts()).expense;transaction('liability','100000',expense,liability);transaction('payment','100000',liability);assert.equal(totals(entries(),accounts()).expense,before+100000n);assert.equal(balance(entries(),liability),0n);});
test('재무상태표 자산 = 부채 + 순자산 + 운영결과',()=>{const s=balanceSheet(entries(),accounts());assert.equal(s.assets,s.liabilities+s.equity+s.result);invariant();});
test('월간 보고와 원장 합계 일치',()=>{const t=totals(entries(),accounts(),`${year}-01-01`,`${year}-01-31`);assert.equal(t.expense,balance(entries(),expense));assert.equal(t.income,-balance(entries(),income));invariant();});
test('연간 보고는 12개월 합계와 일치',()=>{const monthly=Array.from({length:12},(_,i)=>totals(entries().filter(e=>Number(e.date.slice(5,7))===i+1),accounts()));assert.equal(monthly.reduce((s,t)=>s+t.income,0n),totals(entries(),accounts()).income);assert.equal(monthly.reduce((s,t)=>s+t.expense,0n),totals(entries(),accounts()).expense);invariant();});
test('취소는 원거래 보존 + 역분개, 이중 취소 차단',()=>{const id=transaction('income','33333',income),before=totals(entries(),accounts()).income;mutate('reverse',{id,date,reason:'입력 실수'});assert.equal(totals(entries(),accounts()).income,before-33333n);assert.throws(()=>mutate('reverse',{id,date,reason:'중복'}));invariant();});
test('불균형 분개 및 유효하지 않은 날짜 거절',()=>{const n=entries().length;assert.throws(()=>mutate('post',{date,kind:'journal',description:'잘못된 분개',lines:[{account_id:bank,debit:'1',credit:'0'},{account_id:income,debit:'0',credit:'2'}]}));assert.throws(()=>transaction('income','1.1',income));assert.throws(()=>mutate('post',{date:`${year}-02-30`,kind:'income',amount:'1',cash_id:cash,category_id:income,description:'오류'}));assert.equal(entries().length,n);invariant();});
test('닫힌 연도에 쓰기 차단',()=>{db().prepare("UPDATE years SET status='closed' WHERE year=?").run(year);assert.throws(()=>transaction('income','1',income));db().prepare("UPDATE years SET status='open' WHERE year=?").run(year);invariant();});
test('은행 원본 날짜·금액 위조는 반영 거절',()=>{assert.throws(()=>mutate('post',{date,kind:'income',amount:'999',cash_id:cash,category_id:income,description:'오류',row_id:'row2'}));invariant();});
test('금액은 안전정수 범위보다 커도 정확',()=>{assert.equal(money('9007199254740993')+1n,9007199254740994n);assert.throws(()=>money('1e6'));});
test('CSV 열 매핑과 형식 검증',async()=>{const csv=Buffer.from(`날짜,적요,입금,출금,잔액\n${date},헌금,"100,000",0,"100,000"`);const rows=await parseFile(csv,'test.csv');const row=normalizeRow(rows[1],{date:0,description:1,deposit:2,withdrawal:3,balance:4,time:-1});assert.equal(row.deposit,'100000');assert.equal(row.date,date);assert.throws(()=>normalizeRow([date,'오류','1','1','0'],{date:0,description:1,deposit:2,withdrawal:3,balance:4}));});
test('상하위 예산 중복 설정 차단',()=>{const parent=account('500','관리비','expense');mutate('account',{code:'502',name:'수도료',type:'expense',parent_id:parent});const child=String(db().prepare("SELECT id FROM accounts WHERE code='502'").get()!.id);mutate('budget',{year,account_id:parent,amount:'1000000'});assert.throws(()=>mutate('budget',{year,account_id:child,amount:'100000'}));invariant();});
test('은행 원본과 원장·이력 직접 수정 차단',()=>{assert.throws(()=>db().exec("UPDATE bank_rows SET description='변조'"));assert.throws(()=>db().exec("DELETE FROM entries"));assert.throws(()=>db().exec('DELETE FROM audits'));invariant();});

test('XLSX 실제 파일 파싱 및 금액 정규화',async()=>{const {default:ExcelJS}=await import('exceljs');const book=new ExcelJS.Workbook();const sheet=book.addWorksheet('거래내역');sheet.addRows([['날짜','적요','입금','출금','잔액'],[date,'헌금',100000,0,100000]]);const rows=await parseFile(Buffer.from(await book.xlsx.writeBuffer()),'test.xlsx');assert.equal(normalizeRow(rows[1],{date:0,description:1,deposit:2,withdrawal:3,balance:4}).deposit,'100000');invariant();});
