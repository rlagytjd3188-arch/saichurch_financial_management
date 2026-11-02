export type AccountType='asset'|'liability'|'equity'|'income'|'expense';
export type Account={id:string;code:string;name:string;type:AccountType;parent_id:string|null;active:number};
export type Line={account_id:string;debit:string;credit:string};
export type Entry={id:string;date:string;kind:string;description:string;counterparty:string;lines:Line[];reverses_id?:string|null};
export function money(value:unknown):bigint {
 const s=String(value??'').replaceAll(',','').trim();
 if(!/^\d+$/.test(s))throw Error('금액은 0 이상의 원 단위 정수로 입력하세요.');
 const n=BigInt(s);if(n>9000000000000000000n)throw Error('금액이 허용 범위를 초과했습니다.');return n;
}
export function validDate(s:string){if(!/^\d{4}-\d{2}-\d{2}$/.test(s)||!Number.isFinite(Date.parse(s))||new Date(s).toISOString().slice(0,10)!==s)throw Error('올바른 거래일을 입력하세요.');return s;}
export function validateLines(lines:Line[]){
 if(lines.length<2)throw Error('분개는 두 행 이상이어야 합니다.');let d=0n,c=0n;
 for(const l of lines){const a=money(l.debit),b=money(l.credit);if((a===0n)===(b===0n))throw Error('분개 행은 차변 또는 대변 한쪽에만 금액이 있어야 합니다.');d+=a;c+=b;}
 if(d!==c)throw Error('차변과 대변 합계가 다릅니다.');return d;
}
export function automaticLines(kind:string,amount:string,cash:string,category:string):Line[]{
 const n=money(amount);if(n===0n)throw Error('금액은 0원보다 커야 합니다.');if(cash===category)throw Error('서로 다른 계정을 선택하세요.');
 const debit=['income','transfer','payment'].includes(kind)?(kind==='income'?cash:category):category;
 const credit=['income','transfer','payment'].includes(kind)?(kind==='income'?category:cash):cash;
 const lines=[{account_id:debit,debit:n.toString(),credit:'0'},{account_id:credit,debit:'0',credit:n.toString()}];validateLines(lines);return lines;
}
export function balance(entries:Entry[],id:string,until='9999-12-31'){return entries.filter(e=>e.date<=until).reduce((s,e)=>s+e.lines.filter(l=>l.account_id===id).reduce((v,l)=>v+BigInt(l.debit)-BigInt(l.credit),0n),0n);}
export function descendants(accounts:Account[],id:string):string[]{return [id,...accounts.filter(a=>a.parent_id===id).flatMap(a=>descendants(accounts,a.id))];}
export function totals(entries:Entry[],accounts:Account[],from='0000-01-01',to='9999-12-31'){
 const period=entries.filter(e=>e.date>=from&&e.date<=to&&e.kind!=='opening');
 const sum=(type:AccountType)=>accounts.filter(a=>a.type===type).reduce((s,a)=>s+balance(period,a.id),0n);
 return {income:-sum('income'),expense:sum('expense')};
}
export function balanceSheet(entries:Entry[],accounts:Account[],until='9999-12-31'){
 const sum=(type:AccountType)=>accounts.filter(a=>a.type===type).reduce((s,a)=>s+balance(entries,a.id,until),0n);
 const assets=sum('asset'),liabilities=-sum('liability'),equity=-sum('equity'),result=-sum('income')-sum('expense');
 return {assets,liabilities,equity,result,difference:assets-liabilities-equity-result};
}
export function budgetExecution(entries:Entry[],accounts:Account[],id:string,amount:string){const ids=descendants(accounts,id);const actual=ids.reduce((s,a)=>s+balance(entries,a),0n),budget=money(amount);return {actual,budget,remaining:budget-actual,status:budget===0n?'unset':actual>=budget?'over':actual*100n>=budget*80n?'warning':'normal'};}
export function suggest(description:string,rules:{pattern:string;account_id:string}[]){return rules.find(r=>description.toLocaleLowerCase().includes(r.pattern.toLocaleLowerCase()))?.account_id??'';}
