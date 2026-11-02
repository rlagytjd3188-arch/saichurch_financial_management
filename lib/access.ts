import {cookies} from 'next/headers';
import {createClient} from '@supabase/supabase-js';
export const viewer=()=>process.env.APP_MODE==='viewer';
export function localOnly(req:Request){
 if(viewer())throw Error('외부 조회 화면에서는 수정할 수 없습니다.');
 const url=new URL(req.url);const host=req.headers.get('host')?.split(':')[0];
 if(!['localhost','127.0.0.1'].includes(host||'')||!['localhost','127.0.0.1'].includes(url.hostname))throw Error('PC의 로컬 주소에서만 접근할 수 있습니다.');
 const origin=req.headers.get('origin');if(origin&&new URL(origin).host!==req.headers.get('host'))throw Error('허용되지 않은 요청입니다.');
 if(req.headers.get('sec-fetch-site')==='cross-site')throw Error('허용되지 않은 요청입니다.');
}
export function supabase(service=false){const url=process.env.SUPABASE_URL;const key=service?process.env.SUPABASE_SERVICE_ROLE_KEY:process.env.SUPABASE_ANON_KEY;if(!url||!key)throw Error('Supabase 환경 설정이 필요합니다.');return createClient(url,key,{auth:{persistSession:false,autoRefreshToken:false}});}
export async function viewerSnapshot(){const token=(await cookies()).get('viewer_token')?.value;if(!token)throw Error('로그인이 필요합니다.');const base=supabase();const {data,error}=await base.auth.getUser(token);if(error||!data.user)throw Error('로그인이 만료되었습니다.');const client=createClient(process.env.SUPABASE_URL!,process.env.SUPABASE_ANON_KEY!,{global:{headers:{Authorization:`Bearer ${token}`}},auth:{persistSession:false}});const r=await client.from('published_snapshots').select('payload,published_at').eq('id',1).single();if(r.error)throw Error('조회 권한이 없거나 게시 자료가 없습니다.');return {...r.data.payload,last_published:r.data.published_at};}
export function failure(e:unknown,status=400){console.error(e instanceof Error?e.message:'Request failed');const message=e instanceof Error?e.message:'처리하지 못했습니다.';return Response.json({error:/UNIQUE/.test(message)?'이미 등록된 자료입니다. 중복 여부를 확인하세요.':message},{status});}
