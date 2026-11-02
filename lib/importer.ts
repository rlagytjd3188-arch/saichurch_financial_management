import ExcelJS from 'exceljs';
import {parse} from 'csv-parse/sync';
import {validDate,money} from './accounting.ts';
export async function parseFile(buffer:Buffer,name:string):Promise<string[][]>{
 if(buffer.length>10*1024*1024)throw Error('파일은 10MB 이하로 선택하세요.');
 let rows:string[][]=[];
 if(name.toLowerCase().endsWith('.csv')){let text=buffer.toString('utf8');if(text.includes('\uFFFD'))text=new TextDecoder('euc-kr').decode(buffer);rows=parse(text,{bom:true,skip_empty_lines:true,relax_column_count:true});}
 else if(name.toLowerCase().endsWith('.xlsx')){const book=new ExcelJS.Workbook();await book.xlsx.load(buffer as never);const sheet=book.worksheets[0];if(!sheet)throw Error('첫 번째 시트가 비어 있습니다.');sheet.eachRow(row=>{const cells:string[]=[];for(let i=1;i<=sheet.columnCount;i++){const c=row.getCell(i);cells.push(c.value instanceof Date?c.value.toISOString().slice(0,10):c.text);}rows.push(cells);});}
 else throw Error('CSV 또는 XLSX 파일만 지원합니다.');
 if(rows.length>10001)throw Error('한 번에 10,000행까지 가져올 수 있습니다.');if(rows.length<2)throw Error('헤더와 거래 데이터가 필요합니다.');return rows;
}
export function normalizeRow(row:string[],map:Record<string,number>){
 const get=(k:string)=>map[k]>=0?String(row[map[k]]??'').trim():'';
 const date=get('date').replace(/[./]/g,'-').slice(0,10).split('-').map((x,i)=>i?x.padStart(2,'0'):x).join('-');validDate(date);
 const amount=(k:string)=>money(get(k).replace(/[원\s]/g,'')||'0').toString();
 const deposit=amount('deposit'),withdrawal=amount('withdrawal');if((deposit==='0')===(withdrawal==='0'))throw Error('입금 또는 출금 한쪽에만 금액이 있어야 합니다.');
 const rawBalance=get('balance').replace(/[,원\s]/g,'');if(!/^-?\d+$/.test(rawBalance))throw Error('거래 후 잔액 열이 필요합니다.');
 return {date,time:get('time'),description:get('description')||'은행 거래',deposit,withdrawal,balance:BigInt(rawBalance).toString()};
}
