import ExcelJS from 'exceljs';
import {createHash} from 'node:crypto';
import {validDate} from './accounting.ts';
export type WorkbookRow={sheet:string;row:number;date:string;time:string;description:string;memo:string;deposit:string;withdrawal:string;balance:string;raw:string[];suggested_code?:string};
export type Guide={code:string;name:string;type:'income'|'expense'};
export type WorkbookBudget={key:string;name:string;detail:string;amount:string;note:string};
export type WorkbookPlan={version:1;hash:string;sheets:{name:string;count:number;years:number[]}[];rows:WorkbookRow[];guide:Guide[];budgets:WorkbookBudget[];budgetYear:number|null;warnings:string[];skipped:{sheet:string;row:number;reason:string}[];errors:{sheet:string;row:number;reason:string}[]};
function value(c:ExcelJS.Cell):unknown{const v=c.value;if(v instanceof Date)return v.toISOString().slice(0,19).replace('T',' ');if(v&&typeof v==='object'){if('formula'in v||'sharedFormula'in v)return 'result'in v?v.result:'';if('richText'in v)return v.richText.map(t=>t.text).join('');if('error'in v)throw Error('원본 셀에 수식 오류가 있습니다.');}return v??'';}
function integer(v:unknown,blank=false){if(v===''&&blank)return 0n;if(typeof v==='number'&&!Number.isSafeInteger(v))throw Error('원 단위 정수가 아닙니다.');const s=String(v).replace(/[,원\s]/g,'');if(!/^-?\d+$/.test(s))throw Error('금액이 정수가 아니거나 수식 결과가 없습니다.');return BigInt(s);}
export async function analyzeWorkbook(buffer:Buffer):Promise<WorkbookPlan>{
 if(buffer.length>10*1024*1024)throw Error('10MB 이하의 XLSX 파일을 선택하세요.');
 const book=new ExcelJS.Workbook();await book.xlsx.load(buffer as never);
 const plan:WorkbookPlan={version:1,hash:createHash('sha256').update(buffer).digest('hex'),sheets:[],rows:[],guide:[],budgets:[],budgetYear:null,warnings:[],skipped:[],errors:[]};
 for(const sheet of book.worksheets){
  if(['거래일시','구분','거래금액','거래 후 잔액'].some((h,i)=>sheet.getRow(1).getCell(i+1).text.trim()!==h))continue;
  let count=0;const years=new Set<number>();
  sheet.eachRow((r,n)=>{if(n===1)return;const raw=Array.from({length:7},(_,i)=>{try{return String(value(r.getCell(i+1)));}catch{return '#ERROR!';}});if(raw.every(v=>!v.trim()))return;
   // Ignore side-by-side helper formulas and summaries, never treat them as transactions.
   if(!raw[0])return;
   try{
    const dateTime=raw[0].trim().replaceAll('.','-').replaceAll('/','-');const date=validDate(dateTime.slice(0,10));const time=dateTime.slice(11,19);
    if(time&&!/^\d{2}:\d{2}:\d{2}$/.test(time))throw Error('거래 시간을 확인하세요.');
    const amount=integer(value(r.getCell(3))),after=integer(value(r.getCell(4)));
    if(amount===0n){plan.skipped.push({sheet:sheet.name,row:n,reason:'0원 거래 (이자 0원 등)'});return;}
    if(!['입금','출금'].includes(raw[1])||(raw[1]==='입금')!==(amount>0n))throw Error('입출금 구분과 거래금액 부호가 다릅니다.');
    const row:WorkbookRow={sheet:sheet.name,row:n,date,time,description:raw[5]||raw[4]||'은행 거래',memo:raw[6],deposit:amount>0n?amount.toString():'0',withdrawal:amount<0n?(-amount).toString():'0',balance:after.toString(),raw};plan.rows.push(row);years.add(Number(date.slice(0,4)));count++;
   }catch(e){plan.errors.push({sheet:sheet.name,row:n,reason:(e as Error).message});}
  });plan.sheets.push({name:sheet.name,count,years:[...years].sort()});
 }
 if(!plan.sheets.length)throw Error('지원하는 통장 시트를 찾지 못했습니다. 첫 행이 거래일시 / 구분 / 거래금액 / 거래 후 잔액인지 확인하세요.');
 if(plan.rows.length>20000)throw Error('한 번에 20,000건까지 분석할 수 있습니다.');
 const guide=book.getWorksheet('계정 가이드');guide?.eachRow((r,n)=>{if(n===1)return;for(const [nameCol,codeCol,type] of [[1,2,'income'],[3,4,'expense']] as const){const code=r.getCell(codeCol).text.trim(),name=r.getCell(nameCol).text.trim();if(!code||!name)continue;if(['100','221'].includes(code))continue;if(!/^\d+$/.test(code))continue;plan.guide.push({code,name,type});}});
 // Existing classified ledger is a recommendation source, never another transaction source.
 const legacy=book.getWorksheet('내역');const hints=new Map<string,Set<string>>();
 legacy?.eachRow((r,n)=>{if(n<16)return;try{const date=validDate(String(value(r.getCell(1))).replaceAll('.','-').slice(0,10)),code=r.getCell(2).text.trim();const g=plan.guide.find(g=>g.code===code);if(!g)return;const amount=integer(value(r.getCell(g.type==='income'?4:5)),true);if(amount<=0n)return;const key=`${date}|${g.type}|${amount}`;if(!hints.has(key))hints.set(key,new Set());hints.get(key)!.add(code);}catch{/* Non-transaction and subtotal rows are not imported. */}});
 for(const row of plan.rows){const codes=hints.get(`${row.date}|${row.deposit!=='0'?'income':'expense'}|${row.deposit!=='0'?row.deposit:row.withdrawal}`);if(codes?.size===1)row.suggested_code=[...codes][0];}
 const budget=book.getWorksheet('예산집행');if(budget){const y=budget.getCell('A1').text.match(/(20\d{2})년/);plan.budgetYear=y?Number(y[1]):null;for(let n=3;n<=15;n++){const r=budget.getRow(n),name=r.getCell(7).text.trim();if(!name||name==='합계')continue;try{const amount=integer(value(r.getCell(9)));if(amount<0n)throw Error('음수 예산');plan.budgets.push({key:`budget-${n}`,name,detail:r.getCell(8).text.trim(),amount:amount.toString(),note:r.getCell(10).text.trim()});}catch(e){plan.warnings.push(`예산집행 ${n}행: ${(e as Error).message}`);}}}
 plan.warnings.push('기존 내역의 날짜·입출금 종류·금액이 같고 후보 계정이 하나인 경우에만 분류를 추천합니다. 동일 금액의 다른 거래일 수 있으므로 확인 후 선택하세요.');
 plan.warnings.push('「내역」 및 요약·보고 시트는 통장과 중복되므로 거래로 가져오지 않습니다. 계정별 분류는 통장 거래 검토 화면에서 확인하세요.');
 plan.warnings.push('기존 수식의 계산값과 #REF! 등의 오류는 가져오지 않습니다. 앱 원장에서 보고서를 다시 계산합니다.');
 plan.warnings.push('예산 항목명과 계정 가이드가 다릅니다. 지출예산은 연결할 비용 계정을 직접 확인해야 합니다. 수입 목표는 이번 가져오기에서 제외됩니다.');
 return plan;
}
export function yearSummary(plan:WorkbookPlan,year:number){return plan.sheets.map(s=>{
 const rows=plan.rows.filter(r=>r.sheet===s.name&&Number(r.date.slice(0,4))===year).sort((a,b)=>`${a.date} ${a.time}`.localeCompare(`${b.date} ${b.time}`)||a.row-b.row);
 const first=rows[0],last=rows.at(-1);const deposit=rows.reduce((n,r)=>n+BigInt(r.deposit),0n),withdrawal=rows.reduce((n,r)=>n+BigInt(r.withdrawal),0n);
 const opening=first?BigInt(first.balance)-BigInt(first.deposit)+BigInt(first.withdrawal):0n;
 let expected=opening;let breaks=0;const issues:{row:number;date:string;expected:string;actual:string}[]=[];for(const row of rows){expected+=BigInt(row.deposit)-BigInt(row.withdrawal);if(expected!==BigInt(row.balance)){breaks++;issues.push({row:row.row,date:row.date,expected:expected.toString(),actual:row.balance});expected=BigInt(row.balance);}}
 return {sheet:s.name,count:rows.length,first:first?.date,last:last?.date,opening:opening.toString(),deposit:deposit.toString(),withdrawal:withdrawal.toString(),lastBalance:last?.balance??'0',breaks,issues};
 });}
