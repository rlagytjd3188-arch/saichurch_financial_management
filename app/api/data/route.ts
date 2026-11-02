import {viewer,viewerSnapshot,localOnly,failure} from '../../../lib/access';
export const runtime='nodejs';
export async function GET(req:Request){try{if(viewer())return Response.json(await viewerSnapshot(),{headers:{'Cache-Control':'no-store'}});localOnly(req);const {snapshot}=await import('../../../lib/db');return Response.json(snapshot(),{headers:{'Cache-Control':'no-store'}});}catch(e){return failure(e,403);}}
export async function POST(req:Request){try{localOnly(req);const {mutate}=await import('../../../lib/db');const {action,payload}=await req.json();const result=mutate(action,payload);return Response.json({ok:true,result});}catch(e){return failure(e);}}
