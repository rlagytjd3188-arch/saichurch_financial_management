import {test} from 'node:test';
import assert from 'node:assert/strict';
import ExcelJS from 'exceljs';
import {cellText} from '../lib/excel-cell.ts';
import {parseFile,ChurchWorkbookDetected} from '../lib/importer.ts';
import {analyzeWorkbook} from '../lib/church-workbook.ts';
test('빈 병합 셀과 결과 없는 수식을 빈 문자열로 읽는다',()=>{const b=new ExcelJS.Workbook(),s=b.addWorksheet('sheet');s.mergeCells('A1:B1');assert.equal(cellText(s.getCell('B1')),'');s.getCell('C1').value={formula:'SUM(D1:D2)'};assert.equal(cellText(s.getCell('C1')),'');s.getCell('D1').value={formula:'1+1',result:0};assert.equal(cellText(s.getCell('D1')),'0');});
test('일반 XLSX 경로도 빈 병합 셀에서 toString 예외가 발생하지 않는다',async()=>{const b=new ExcelJS.Workbook(),s=b.addWorksheet('sheet');s.addRows([['날짜','적요'],['2026-01-01','테스트']]);s.mergeCells('C1:D1');const rows=await parseFile(Buffer.from(await b.xlsx.writeBuffer()),'bank.xlsx');assert.equal(rows[0][3],'');});
test('병합된 요약 시트가 앞에 있어도 기존 교회 장부로 인식한다',async()=>{const b=new ExcelJS.Workbook(),summary=b.addWorksheet('정리한 것');summary.mergeCells('A1:B1');const s=b.addWorksheet('운영통장 내역');s.addRows([['거래일시','구분','거래금액','거래 후 잔액'],['2026.01.01 12:00:00','입금',1000,1000,'입금','테스트']]);const data=Buffer.from(await b.xlsx.writeBuffer());await assert.rejects(()=>parseFile(data,'교회.xlsx'),ChurchWorkbookDetected);const p=await analyzeWorkbook(data);assert.equal(p.rows.length,1);assert.equal(p.errors.length,0);});
