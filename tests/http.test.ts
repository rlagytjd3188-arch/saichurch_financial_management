import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readJson} from '../lib/http.ts';
test('JSON success and API errors remain readable',async()=>{
 assert.deepEqual(await readJson(Response.json({entries:[]})),{entries:[]});
 assert.deepEqual(await readJson(Response.json({error:'권한 없음'},{status:403})),{error:'권한 없음'});
});
test('HTML server errors explain what to check instead of a JSON parser error',async()=>{
 await assert.rejects(()=>readJson(new Response('<!DOCTYPE html><h1>Internal Server Error</h1>',{status:500,headers:{'content-type':'text/html'}})),e=>{assert.ok(e instanceof Error);assert.match(e.message,/HTTP 500/);assert.match(e.message,/실행 창/);assert.doesNotMatch(e.message,/DOCTYPE|Unexpected token/);return true;});
});
test('404 and malformed JSON responses are handled explicitly',async()=>{
 await assert.rejects(()=>readJson(new Response('<html>Not found</html>',{status:404})),/데이터 경로.*HTTP 404/);
 await assert.rejects(()=>readJson(new Response('{bad',{headers:{'content-type':'application/json'}})),/올바른 데이터 응답/);
});

test('data API is included in the repository while real database stays ignored',async()=>{
 const {execFileSync}=await import('node:child_process');
 const result=execFileSync('git',['check-ignore','--no-index','--stdin'],{input:'data/finance.sqlite\napp/api/data/route.ts\n',encoding:'utf8'});
 assert.equal(result.trim(),'data/finance.sqlite');
});
