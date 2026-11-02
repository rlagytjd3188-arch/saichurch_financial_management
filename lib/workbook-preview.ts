import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {analyzeManagedLedger} from './managed-ledger.ts';
import {db} from './db.ts';
import {analyzeWorkbook,yearSummary} from './church-workbook.ts';
export async function createWorkbookPreview(buffer:Buffer,name:string){
 if(buffer.length>10*1024*1024)throw Error('10MB 이하의 XLSX 파일을 선택하세요.');
 const managed=await analyzeManagedLedger(buffer);
 const plan=managed??await analyzeWorkbook(buffer),token=randomUUID();
 const dir=path.join(process.env.DATA_DIR||path.join(process.cwd(),'data'),'workbook-previews');
 await mkdir(dir,{recursive:true,mode:0o700});await writeFile(path.join(dir,`${token}.xlsx`),buffer,{mode:0o600,flag:'wx'});
 if(managed)return {...managed,token,name,importedKeys:db().prepare('SELECT key FROM managed_sources').all().map(r=>String(r.key))};
 const bank=plan as Awaited<ReturnType<typeof analyzeWorkbook>>;
 const years=[...new Set(bank.rows.map(r=>Number(r.date.slice(0,4))))].sort();
 return {token,name,hash:bank.hash,sheets:bank.sheets,years,summaries:Object.fromEntries(years.map(y=>[y,yearSummary(bank,y)])),guide:bank.guide,budgets:bank.budgets,budgetYear:bank.budgetYear,warnings:bank.warnings,skipped:bank.skipped,errors:bank.errors,previews:Object.fromEntries(years.map(y=>[y,bank.rows.filter(r=>r.date.startsWith(`${y}-`)).slice(0,20).map(({raw,...r})=>r)]))};
}
