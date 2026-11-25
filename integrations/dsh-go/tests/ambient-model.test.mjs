import assert from 'node:assert/strict'
import {isAmbientHint, ambientOverride, selectHintRoute} from '../lib/ambient-model.js'
let n = 0
function eq(a,b){assert.deepEqual(a,b);n++}
function throws(fn){assert.throws(fn);n++}
async function rejects(fn){await assert.rejects(fn);n++}
const ambient = {kind:'hint',input:{source:'ALWAYS_ON',voiceContext:{segments:[]},hintDeadline:123}}
const real = {kind:'hint',input:{...ambient.input,source:'REALTIME',streamHint:true}}
const review = {kind:'hint',input:{purpose:'record_followup',segments:[]}}
const config = {ambientProvider:' registered ',ambientModel:' compact ',ambientReasoningEffort:' low '}
const chosen = {provider:'registered',model:'compact',reasoningEffort:'low'}
const defaults = {provider:'default',model:'strong',reasoningEffort:'high'}
let calls = 0
const ctx = {agentDefaultModel:{currentSelection:()=>defaults},llm:{resolveCallConfig:async x=>{calls++;return {...x,maxTokens:500}}}}
for (const v of [ambient,real]) eq(isAmbientHint(v),true)
for (const v of [review,{...ambient,kind:'task'},{...ambient,input:{...ambient.input,backfill:true}},{...ambient,input:{...ambient.input,voiceContext:null}},{...ambient,input:{...ambient.input,hintDeadline:'123'}},{...real,input:{...real.input,streamHint:false}},{kind:'route'},{kind:'hint'}]) eq(isAmbientHint(v),false)
eq(ambientOverride({}),null)
eq(ambientOverride(config),chosen)
eq(ambientOverride({ambientProvider:'p',ambientModel:'m'}),{provider:'p',model:'m'})
for(const c of [{ambientProvider:'p'},{ambientModel:'m'},{ambientReasoningEffort:'low'},{...config,ambientModel:'m\n'},{...config,ambientProvider:5},{...config,ambientModel:'a'.repeat(257)}])throws(()=>ambientOverride(c))
eq(await selectHintRoute(ctx,{},ambient),defaults)
eq(await selectHintRoute(ctx,config,ambient),{...chosen,maxTokens:500})
eq(await selectHintRoute(ctx,config,real),{...chosen,maxTokens:500})
eq(await selectHintRoute(ctx,config,review),defaults)
eq(await selectHintRoute(ctx,{ambientProvider:'invalid'},review),defaults)
eq(await selectHintRoute(ctx,config,{kind:'route'}),defaults)
eq(calls,2)
await rejects(()=>selectHintRoute(ctx,{ambientProvider:'p'},ambient))
await rejects(()=>selectHintRoute({...ctx,llm:{}},config,ambient))
await rejects(()=>selectHintRoute({...ctx,llm:{resolveCallConfig:async()=>{throw Error('unsupported')}}},config,ambient))
await rejects(()=>selectHintRoute({...ctx,llm:{resolveCallConfig:async()=>defaults}},config,ambient))
await rejects(()=>selectHintRoute({...ctx,llm:{resolveCallConfig:async()=>({...chosen,reasoningEffort:'high'})}},config,ambient))
const c = new AbortController();c.abort()
await rejects(()=>selectHintRoute(ctx,config,ambient,c.signal))
const c2 = new AbortController()
await rejects(()=>selectHintRoute({...ctx,llm:{resolveCallConfig:async x=>{c2.abort();return x}}},config,ambient,c2.signal))
eq(calls,2)
console.log(`${n} model-route assertions passed`)
