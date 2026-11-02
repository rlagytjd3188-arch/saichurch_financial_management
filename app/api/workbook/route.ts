import {randomUUID} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {localOnly,failure} from '../../../lib/access';
import {analyzeWorkbook,yearSummary} from '../../../lib/church-workbook';
export const runtime='nodejs';
export async function POST(req:Request){try{
 localOnly(req);
 const dir=path.join(process.env.DATA_DIR||path.join(process.cwd(),'data'),'workbook-previews');
 if(req.headers.get('content-type')?.includes('multipart/form-data')){
 const form=await req.formData(),file=form.get('file') as File;
 if(!file||!file.name.toLowerCase().endsWith('.xlsx')||file.size>10*1024*1024)throw Error('10MB 이하의 XLSX 파일을 선택하세요.');
 const buffer=Buffer.from(await file.arrayBuffer()),plan=await analyzeWorkbook(buffer),token=randomUUID();
 await mkdir(dir,{recursive:true,mode:0o700});await writeFile(path.join(dir,`${token}.xlsx`),buffer,{mode:0o600,flag:'wx'});
 const years=[...new Set(plan.rows.map(r=>Number(r.date.slice(0,4))))].sort();
 return Response.json({token,name:file.name,hash:plan.hash,sheets:plan.sheets,years,summaries:Object.fromEntries(years.map(y=>[y,yearSummary(plan,y)])),guide:plan.guide,budgets:plan.budgets,budgetYear:plan.budgetYear,warnings:plan.warnings,skipped:plan.skipped,errors:plan.errors,previews:Object.fromEntries(years.map(y=>[y,plan.rows.filter(r=>r.date.startsWith(`${y}-`)).slice(0,20).map(({raw,...r})=>r)]))});
 }
 const {token,options}=await req.json();if(typeof token!=='string'||! /^[0-9a-f-]{36}$/.test(token)||!options)throw Error('파일을 다시 업로드하세요.');
 const buffer=await readFile(path.join(dir,`${token}.xlsx`)).catch(()=>{throw Error('업로드 파일이 없습니다. 다시 업로드하세요.');});
 const plan=await analyzeWorkbook(buffer);const {importWorkbook}=await import('../../../lib/workbook-store');return Response.json({ok:true,...importWorkbook(plan,options)});
 }catch(e){return failure(e);}}
