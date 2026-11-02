import ExcelJS from 'exceljs';
import {chromium} from 'playwright-core';
import assert from 'node:assert/strict';
import {mkdirSync,mkdtempSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
const base=process.env.TEST_URL||'http://127.0.0.1:3105';
const server=process.env.TEST_URL?null:spawn(process.execPath,['node_modules/next/dist/bin/next','start','--hostname','127.0.0.1','--port','3105'],{env:{...process.env,APP_MODE:'local',DATA_DIR:mkdtempSync(path.join(tmpdir(),'church-browser-'))},stdio:'ignore'});
process.on('exit',()=>server?.kill());
for(let i=0;i<100;i++){try{const r=await fetch(base);if(r.ok)break;}catch{}if(i===99)throw Error('Test server did not start');await new Promise(r=>setTimeout(r,200));}
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const page=await browser.newPage({viewport:{width:1440,height:1100}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
async function api(action,payload){const r=await page.request.post(`${base}/api/data`,{data:{action,payload}});assert.equal(r.status(),200,await r.text());}
try{
 await page.goto(base);await page.getByText('우리 교회에 맞는 첫 원장을 준비하세요').waitFor();
 await page.getByRole('button',{name:'계정과목 추가',exact:true}).count();
 await page.getByRole('button',{name:'계정과목 등록'}).click();
 await page.getByLabel('계정 코드').fill('101');await page.getByLabel('계정 이름').fill('운영예금');await page.getByRole('button',{name:'저장',exact:true}).click();await page.getByRole('dialog').waitFor({state:'hidden'});
 await api('account',{code:'102',name:'정기예금',type:'asset'});await api('account',{code:'301',name:'전기이월순자산',type:'equity'});await api('account',{code:'401',name:'약정헌금',type:'income'});await api('account',{code:'501',name:'전기료',type:'expense'});
 let data=await(await page.request.get(`${base}/api/data`)).json();const id=n=>data.accounts.find(a=>a.name===n).id;
 await api('cash',{name:'국민은행 운영계좌',institution:'국민은행',ledger_account_id:id('운영예금')});await api('cash',{name:'정기예금',institution:'국민은행',ledger_account_id:id('정기예금')});
 await page.reload();await page.getByRole('button',{name:'거래 입력',exact:true}).click();
 let dialog=page.getByRole('dialog');await dialog.getByLabel('거래 종류').selectOption('income');await dialog.getByLabel('금액 (원)').fill('100000');await dialog.getByLabel('적요 / 거래내용').fill('주일 약정헌금');await dialog.getByLabel('자금 계좌').selectOption({label:'국민은행 운영계좌'});await dialog.getByLabel('분류',{exact:true}).selectOption({label:'약정헌금'});await dialog.getByRole('button',{name:'장부에 반영'}).click();await dialog.waitFor({state:'hidden'});
 await page.getByRole('button',{name:'거래 입력',exact:true}).click();dialog=page.getByRole('dialog');await dialog.getByLabel('거래 종류').selectOption('expense');await dialog.getByLabel('금액 (원)').fill('50000');await dialog.getByLabel('적요 / 거래내용').fill('한국전력 1월');await dialog.getByLabel('자금 계좌').selectOption({label:'국민은행 운영계좌'});await dialog.getByLabel('분류',{exact:true}).selectOption({label:'전기료'});await dialog.getByRole('button',{name:'장부에 반영'}).click();await dialog.waitFor({state:'hidden'});
 await page.getByRole('button',{name:'대시보드',exact:true}).click();await page.getByText(/100,000/).first().waitFor();mkdirSync('/tmp/church-artifacts',{recursive:true});await page.screenshot({path:'/tmp/church-artifacts/dashboard-desktop.png',fullPage:true});
 await page.getByRole('button',{name:'전체 장부',exact:true}).click();await page.getByText('주일 약정헌금',{exact:true}).click();await page.getByRole('dialog').getByText('차변·대변 합계가 일치하는 반영된 분개입니다.').waitFor();await page.getByRole('button',{name:'닫기',exact:true}).click();
 for(const kind of ['ledger','balance','report','budget'])for(const format of ['csv','xlsx']){const r=await page.request.get(`${base}/api/export?kind=${kind}&format=${format}&year=${new Date().getFullYear()}`);assert.equal(r.status(),200);assert.ok((await r.body()).length>0);}
 const year=new Date().getFullYear();data=await(await page.request.get(`${base}/api/data`)).json();const cash=data.financial_accounts.find(c=>c.name==='국민은행 운영계좌').id;
 const csv=`날짜,적요,입금,출금,잔액\n${year}-01-06,감사헌금,30000,0,80000`;
 const upload=await page.request.post(`${base}/api/import`,{multipart:{file:{name:'sample.csv',mimeType:'text/csv',buffer:Buffer.from(csv)}}});assert.equal(upload.status(),200);const parsed=await upload.json();
 const stage=await page.request.post(`${base}/api/import`,{data:{action:'stage',...parsed,cash_id:cash,map:{date:0,description:1,deposit:2,withdrawal:3,balance:4,time:-1}}});assert.equal(stage.status(),200,await stage.text());
 await page.reload();await page.getByRole('button',{name:/미분류 거래/}).first().click();await page.getByText('감사헌금',{exact:true}).waitFor();
 const bankReview=page.locator('tr').filter({has:page.getByText('감사헌금',{exact:true})});await bankReview.locator('select').nth(1).selectOption(id('약정헌금'));await page.getByRole('button',{name:'선택한 거래 장부 반영'}).click();await page.getByText('모든 거래가 분류되었습니다',{exact:true}).waitFor();
 const backup=await page.request.get(`${base}/api/export?kind=backup`);assert.equal(backup.status(),200);assert.equal((await backup.json()).entries.length,3);

 await page.getByRole('button',{name:'은행내역 가져오기',exact:true}).click();
 const workbook=new ExcelJS.Workbook();const summarySheet=workbook.addWorksheet('정리한 것');summarySheet.mergeCells('A1:B1');const ws=workbook.addWorksheet('이전통장 내역');ws.addRows([['거래일시','구분','거래금액','거래 후 잔액','거래구분','내용','메모'],['2088.01.01 09:00:00','입금',100000,100000,'입금','이전 장부 거래','테스트 원본']]);const guideSheet=workbook.addWorksheet('계정 가이드');guideSheet.addRows([['수입 계정','코드','지출 계정','코드'],['이전수익',9801,'이전비용',9802]]);
 const chooserPromise=page.waitForEvent('filechooser');await page.getByRole('button',{name:'파일 선택',exact:true}).click();const chooser=await chooserPromise;await chooser.setFiles({name:'synthetic-workbook.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from(await workbook.xlsx.writeBuffer())});
 await page.getByLabel('기존 장부 회계연도').waitFor();await page.getByLabel('계좌 연결·기초잔액·예산 연결과 기존 거래 중복 여부를 확인했습니다.').check();await page.getByRole('button',{name:'확인한 설정과 원본 가져오기',exact:true}).click();await page.getByText(/2088년 원본 1건을 저장했습니다/).waitFor();
 const migrated=await(await page.request.get(`${base}/api/data`)).json();assert.equal(migrated.entries.length,3);assert.equal(migrated.bank_rows.filter(r=>r.date.startsWith('2088')).length,1);assert.ok(migrated.accounts.some(a=>a.code==='9801'));
 const bad=await page.request.post(`${base}/api/data`,{headers:{Origin:'https://evil.example'},data:{action:'year',payload:{year:2099}}});assert.equal(bad.status(),400);
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'메뉴',exact:true}).click();await page.getByRole('button',{name:'대시보드',exact:true}).click();await page.screenshot({path:'/tmp/church-artifacts/dashboard-mobile.png',fullPage:true});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'mobile overflow');assert.deepEqual(errors,[]);
 console.log('PASS: browser forms, journal details, CSV upload/review/posting, backup, multi-sheet migration, 8 exports, cross-origin rejection, mobile layout');
}catch(e){mkdirSync('/tmp/church-artifacts',{recursive:true});await page.screenshot({path:'/tmp/church-artifacts/failure.png',fullPage:true});console.error('Page errors:',errors);throw e;}finally{await browser.close();server?.kill();}
