import ExcelJS from 'exceljs';
import {snapshot,audit,atomic} from '../../../lib/db';
import {localOnly,viewer,viewerSnapshot,failure} from '../../../lib/access';
import {balance,balanceSheet,totals,budgetExecution,type Account,type Entry} from '../../../lib/accounting';
const safe=(v:unknown)=>{const s=String(v??'');return /^[=+@\-\t\r]/.test(s)?`'${s}`:s;};
export async function GET(req:Request){try{
 const url=new URL(req.url),kind=url.searchParams.get('kind')||'ledger',format=url.searchParams.get('format')||'csv';
 if(viewer()&&kind==='backup')throw Error('백업은 PC에서만 가능합니다.');
 let data;if(viewer())data=await viewerSnapshot();else{localOnly(req);data=snapshot();}
 if(kind==='backup'){atomic(()=>audit('백업 생성','backup',null,{at:new Date().toISOString()}));return new Response(JSON.stringify({...snapshot(),backup_notice:'원본 DB와 함께 보관하세요. 자동 복원은 지원하지 않습니다.'},null,2),{headers:{'Content-Type':'application/json','Content-Disposition':'attachment; filename="finance-backup.json"','Cache-Control':'no-store'}});}
 const year=url.searchParams.get('year')||String(new Date().getFullYear()),month=url.searchParams.get('month');const from=month?`${year}-${month}-01`:`${year}-01-01`;const to=month?`${year}-${month}-31`:`${year}-12-31`;
 const accounts=data.accounts as Account[],entries=(data.entries as Entry[]).filter(e=>e.date>=from&&e.date<=to);const names=new Map(accounts.map(a=>[a.id,a.name]));let rows:unknown[][]=[];
 if(kind==='budget'){rows=[['계정','예산','집행액','잔여예산'],...data.budgets.filter((b:any)=>b.year===Number(year)).map((b:any)=>{const x=budgetExecution(entries,accounts,b.account_id,b.amount);return [names.get(b.account_id),x.budget.toString(),x.actual.toString(),x.remaining.toString()];})];}
 else if(kind==='balance'){const s=balanceSheet((data.entries as Entry[]).filter(e=>e.date.slice(0,4)===year),accounts,to);rows=[['재무상태표',to],['자산',s.assets.toString()],['부채',s.liabilities.toString()],['이월순자산',s.equity.toString()],['운영결과',s.result.toString()],['검증차이',s.difference.toString()],['계정','잔액'],...accounts.filter(a=>!['income','expense'].includes(a.type)).map(a=>[a.name,balance((data.entries as Entry[]).filter(e=>e.date.slice(0,4)===year),a.id,to).toString()])];}
 else if(kind==='report'){const t=totals(entries,accounts);rows=[['항목','금액'],['수익',t.income.toString()],['비용',t.expense.toString()],['운영결과',(t.income-t.expense).toString()],...accounts.filter(a=>['income','expense'].includes(a.type)).map(a=>[a.name,(balance(entries,a.id)*(a.type==='income'?-1n:1n)).toString()])];}
 else {rows=[['거래ID','날짜','종류','적요','거래처','계정','차변','대변'],...entries.filter(e=>!['income','expense'].includes(kind)||e.kind===kind).flatMap(e=>e.lines.map(l=>[e.id,e.date,e.kind,e.description,e.counterparty,names.get(l.account_id),l.debit,l.credit]))];}
 if(format==='xlsx'){const book=new ExcelJS.Workbook(),sheet=book.addWorksheet('재정보고');sheet.addRows(rows.map(r=>r.map(safe)));sheet.columns.forEach(c=>c.width=24);sheet.getRow(1).font={bold:true};const buffer=await book.xlsx.writeBuffer();return new Response(buffer as ArrayBuffer,{headers:{'Content-Type':'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','Content-Disposition':`attachment; filename="${kind}-${year}.xlsx"`,'Cache-Control':'no-store'}});}
 return new Response('\uFEFF'+rows.map(r=>r.map(v=>'"'+safe(v).replaceAll('"','""')+'"').join(',')).join('\r\n'),{headers:{'Content-Type':'text/csv; charset=utf-8','Content-Disposition':`attachment; filename="${kind}-${year}.csv"`,'Cache-Control':'no-store'}});
 }catch(e){return failure(e);}}
