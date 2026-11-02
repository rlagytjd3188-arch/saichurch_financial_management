import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {analyzeWorkbook,yearSummary} from './church-workbook.ts';
export async function createWorkbookPreview(buffer:Buffer,name:string){
 const plan=await analyzeWorkbook(buffer),token=randomUUID();
 const dir=path.join(process.env.DATA_DIR||path.join(process.cwd(),'data'),'workbook-previews');
 await mkdir(dir,{recursive:true,mode:0o700});await writeFile(path.join(dir,`${token}.xlsx`),buffer,{mode:0o600,flag:'wx'});
 const years=[...new Set(plan.rows.map(r=>Number(r.date.slice(0,4))))].sort();
 return {token,name,hash:plan.hash,sheets:plan.sheets,years,summaries:Object.fromEntries(years.map(y=>[y,yearSummary(plan,y)])),guide:plan.guide,budgets:plan.budgets,budgetYear:plan.budgetYear,warnings:plan.warnings,skipped:plan.skipped,errors:plan.errors,previews:Object.fromEntries(years.map(y=>[y,plan.rows.filter(r=>r.date.startsWith(`${y}-`)).slice(0,20).map(({raw,...r})=>r)]))};
}
