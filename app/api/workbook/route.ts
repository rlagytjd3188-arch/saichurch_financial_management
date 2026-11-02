import {readFile} from 'node:fs/promises';
import path from 'node:path';
import {localOnly,failure} from '../../../lib/access';
import {analyzeWorkbook} from '../../../lib/church-workbook';
import {createWorkbookPreview} from '../../../lib/workbook-preview';
export const runtime='nodejs';
export async function POST(req:Request){try{
 localOnly(req);
 const dir=path.join(process.env.DATA_DIR||path.join(process.cwd(),'data'),'workbook-previews');
 if(req.headers.get('content-type')?.includes('multipart/form-data')){
 const form=await req.formData(),file=form.get('file') as File;
 if(!file||!file.name.toLowerCase().endsWith('.xlsx')||file.size>10*1024*1024)throw Error('10MB 이하의 XLSX 파일을 선택하세요.');
 return Response.json(await createWorkbookPreview(Buffer.from(await file.arrayBuffer()),file.name));
 }
 const {token,options}=await req.json();if(typeof token!=='string'||! /^[0-9a-f-]{36}$/.test(token)||!options)throw Error('파일을 다시 업로드하세요.');
 const buffer=await readFile(path.join(dir,`${token}.xlsx`)).catch(()=>{throw Error('업로드 파일이 없습니다. 다시 업로드하세요.');});
 const plan=await analyzeWorkbook(buffer);const {importWorkbook}=await import('../../../lib/workbook-store');return Response.json({ok:true,...importWorkbook(plan,options)});
 }catch(e){return failure(e);}}
