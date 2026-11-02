import ExcelJS from 'exceljs';
import {createHash} from 'node:crypto';
import {cellText,cellValue} from './excel-cell.ts';
import {validDate} from './accounting.ts';
import type {Guide,WorkbookBudget} from './church-workbook.ts';
export const REFERENCE_BANKS=['약정헌금 내역','입출금통장 내역','저금통장 내역','선교헌금 내역'];
export type ManagedRow={key:string;row:number;date:string;code:string;description:string;memo:string;deposit:string;withdrawal:string;kind:'income'|'expense'|'transfer'|'unresolved';reason:string;raw:string[]};
export type ManagedPlan={mode:'managed-ledger';version:2;handoverStart:string|null;hash:string;guide:Guide[];rows:ManagedRow[];openings:{row:number;date:string;amount:string}[];budgets:WorkbookBudget[];incomeTargets:WorkbookBudget[];budgetYear:number|null;years:number[];ignoredSheets:string[];references:{name:string;role:string;rows:{row:number;cells:string[]}[]}[];warnings:string[];errors:{sheet:string;row:number;reason:string}[];skipped:{row:number;reason:string}[]};
export function isManagedBook(book:ExcelJS.Workbook){const s=book.getWorksheet('내역');return !!s&&['거래일시','코드','내역','입금','출금'].every((h,i)=>cellText(s.getRow(15).getCell(i+1)).trim()===h);}
function amount(cell:ExcelJS.Cell,optional=false){const v=cellValue(cell);if(v===''&&optional)return 0n;if(typeof v==='number'&&!Number.isSafeInteger(v))throw Error('원 단위 정수가 아닙니다.');const s=String(v).replace(/[,원\s]/g,'');if(!/^\d+$/.test(s))throw Error('금액이 없거나 수식 결과를 확인할 수 없습니다.');return BigInt(s);}
export async function analyzeManagedLedger(buffer:Buffer):Promise<ManagedPlan|null>{
 const book=new ExcelJS.Workbook();await book.xlsx.load(buffer as never);if(!isManagedBook(book))return null;
 const p:ManagedPlan={mode:'managed-ledger',version:2,handoverStart:null,hash:createHash('sha256').update(buffer).digest('hex'),guide:[],rows:[],openings:[],budgets:[],incomeTargets:[],budgetYear:null,years:[],ignoredSheets:REFERENCE_BANKS.filter(n=>book.getWorksheet(n)),references:[],warnings:[],errors:[],skipped:[]};
 book.getWorksheet('계정 가이드')?.eachRow((r,n)=>{if(n===1)return;for(const [nc,cc,type]of[[1,2,'income'],[3,4,'expense']]as const){const code=cellText(r.getCell(cc)).trim(),name=cellText(r.getCell(nc)).trim();if(/^\d+$/.test(code)&&name&&!['100','221'].includes(code))p.guide.push({code,name,type});}});
 const occurrences=new Map<string,number>();
 book.getWorksheet('내역')!.eachRow((r,n)=>{
 if(n<16)return;const raw=Array.from({length:8},(_,i)=>cellText(r.getCell(i+1)));if(raw.every(x=>!x.trim()))return;
 const [dateText,code,description,,,,memo]=raw.map(x=>x.trim());
 if(!dateText){p.skipped.push({row:n,reason:'날짜 없는 소계·항목별 집계 (분개 제외)'});return;}
 try{const date=validDate(dateText.replaceAll('.','-').replaceAll('/','-').slice(0,10));const deposit=amount(r.getCell(4),true),withdrawal=amount(r.getCell(5),true);
 if(code==='100'||/전월\s*잔액|전기\s*이월|이월\s*잔액/.test(description)){if(withdrawal!==0n)throw Error('이월행에 출금이 있습니다.');p.openings.push({row:n,date,amount:deposit.toString()});p.skipped.push({row:n,reason:'이월액: 수입 제외, 최초 연초잔액만 별도 확인'});return;}
 if(deposit===0n&&withdrawal===0n){p.skipped.push({row:n,reason:'금액이 없는 행'});return;}if(deposit>0n&&withdrawal>0n)throw Error('입금과 출금이 동시에 있습니다.');
 const guide=p.guide.find(a=>a.code===code);let kind:ManagedRow['kind']=deposit>0n?'income':'expense',reason='';
 if(code==='221'){kind='transfer';reason='통장간이체: 보내는·받는 실제 계좌 확인 필요';}
 else if(code==='대납'){kind='unresolved';reason='대납: 교회 계좌 지출인지 미지급 발생인지 확인 필요';}
 else if(!guide||guide.type!==kind){kind='unresolved';reason='계정 코드 또는 입출금 방향 확인 필요';}
 const canonical=JSON.stringify([date,code,description,deposit.toString(),withdrawal.toString(),memo]);const occurrence=occurrences.get(canonical)||0;occurrences.set(canonical,occurrence+1);const key=createHash('sha256').update(`${canonical}|${occurrence}`).digest('hex');
 p.rows.push({key,row:n,date,code,description:description||'기존 정리장부 거래',memo,deposit:deposit.toString(),withdrawal:withdrawal.toString(),kind,reason,raw});
 }catch(e){p.errors.push({sheet:'내역',row:n,reason:(e as Error).message});}
 });
 const budget=book.getWorksheet('예산집행');if(budget){const y=cellText(budget.getCell('A1')).match(/(20\d{2})년/);p.budgetYear=y?Number(y[1]):null;for(let n=3;n<=15;n++){const r=budget.getRow(n);for(const [nc,dc,ac,tc,target]of[[2,3,4,5,p.incomeTargets],[7,8,9,10,p.budgets]]as const){const name=cellText(r.getCell(nc)).trim();if(!name||name==='합계')continue;try{target.push({key:`${nc===2?'income':'budget'}-${n}`,name,detail:cellText(r.getCell(dc)).trim(),amount:amount(r.getCell(ac)).toString(),note:cellText(r.getCell(tc)).trim()});}catch(e){p.errors.push({sheet:'예산집행',row:n,reason:(e as Error).message});}}}}
 for(const [name,role]of[['정리한 것','종합 보고·정기 지출·약정 계획 참고'],['예산집행','예산·월별 집계 대조 (거래 재반영 제외)'],['시트19','월별 보고 초안 대조 (거래 재반영 제외)'],['계정 가이드','계정 코드 정의']]as const){const s=book.getWorksheet(name);if(!s)continue;const rows:{row:number;cells:string[]}[]=[];s.eachRow((r,n)=>rows.push({row:n,cells:Array.from({length:s.columnCount},(_,i)=>cellText(r.getCell(i+1)).replace(/\b\d{10,}\b/g,x=>`${'*'.repeat(x.length-4)}${x.slice(-4)}`))}));p.references.push({name,role,rows});}
 p.years=[...new Set([...p.rows.map(r=>Number(r.date.slice(0,4))),...p.openings.map(r=>Number(r.date.slice(0,4)))])].sort();
 if(p.years.includes(2026)){p.handoverStart='2026-08-01';p.warnings.push('관리 시작은 2026년 8월입니다. 1~7월 내역은 참고로만 남기고 반영하지 않습니다. 정확한 시작일과 시작 잔액은 직접 확인하세요.');p.references.push({name:'내역 (인수인계 전)',role:'2026년 8월 이전 기록 · 거래 반영 제외',rows:p.rows.filter(r=>r.date<'2026-08-01').map(r=>({row:r.row,cells:r.raw}))});}
 if(!p.rows.length)throw Error('내역 시트에 가져올 거래가 없습니다.');if(p.rows.length>20000)throw Error('한 번에 20,000건까지 가져올 수 있습니다.');
 p.warnings.push('거래 근거는 「내역」입니다. 참고용 통장 4개는 날짜가 같아도 가져오기·추천·잔액·보고서 계산에서 제외합니다.');
 p.warnings.push('내역의 월별 전월 잔액은 반복 수입으로 넣지 않습니다. 연초 잔액은 별도 선택하며, 엑셀의 누적잔액 수식은 계좌 잔액의 근거로 사용하지 않습니다.');
 p.warnings.push('정리한 것·예산집행의 집행액·시트19는 대조용으로 보존합니다. 거래 장부와 겹치는 월별 합계는 분개로 중복 반영하지 않습니다.');
 p.warnings.push('인수인계 이후 거래는 단일 운영통장에 연결합니다. 실제 금융기관·계좌번호는 추정하지 않으며 가져올 기간과 연결 계좌는 사용자가 확인합니다.');
 p.warnings.push('이전 버전으로 통장 참고자료를 이미 가져온 경우 이 장부와 중복될 수 있습니다. 기존 데이터는 자동 삭제하지 않으며 같은 기간의 중복 원본·거래가 있으면 가져오기를 중단합니다.');return p;
}
