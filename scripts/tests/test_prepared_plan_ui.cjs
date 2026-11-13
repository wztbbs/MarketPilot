// Offline DOM contract test; no server, browser, or paid model calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
class Element {
  constructor(tag) { this.tag=tag; this.children=[]; this.isConnected=true; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children=[]; }
  querySelectorAll(tag) { return this.children.flatMap(child => [...(child.tag===tag?[child]:[]), ...child.querySelectorAll(tag)]); }
  set innerHTML(value) { throw new Error('untrusted plan must render as text'); }
}
const source=fs.readFileSync(path.join(__dirname,'../../src/main/resources/static/agent-run.js'),'utf8');
function setup() {
  const requests=[], timers=[];
  const plan={planId:'plan/1', summary:'创建 <script> 工单', approvalStatus:'PENDING', executionStatus:'NOT_STARTED', fingerprint:'frozen-fingerprint', expiresAt:'2026-11-14T00:00:00Z', calls:[
    {id:'create',tool:'sample.create',summary:'创建工单',arguments:{name:'工单'},status:'NOT_STARTED'},
    {id:'assign',tool:'sample.assign',summary:'指派人员',arguments:{ticketId:'{{create.data.id}}'},dependsOn:['create'],status:'NOT_STARTED'}
  ]};
  const payload={runId:'run',execution_status:'WAITING',status:'WAITING',waitTarget:{kind:'PLAN_APPROVAL',id:'plan/1'},plans:[plan],calls:[{invocationId:'bound-call',preparedPlanId:'plan/1',status:'CONFIRMATION_REQUIRED'}]};
  const window={};
  vm.runInNewContext(source,{window,document:{createElement:tag=>new Element(tag)},setTimeout:callback=>timers.push(callback),fetch:async(url,options)=>{
    requests.push({url,options});const decision=JSON.parse(options.body);
    return {ok:true,json:async()=>({...payload,plans:[{...plan,approvalStatus:decision.approve?'APPROVED':'REJECTED'}]})};
  }});
  const target=new Element('main');window.AgentRun.render(target,payload);
  return {target,requests,timers};
}
function text(node) { return [node.textContent||'',...node.children.map(text)].join('\n'); }
(async()=>{
  for(const approve of [true,false]) {
    const {target,requests}=setup();const buttons=target.querySelectorAll('button');
    assert(text(target).includes('等待方案确认'));
    assert.equal(buttons.length,2,'one approval for whole plan; hide bound per-call confirmation');
    assert(text(target).includes('{{create.data.id}}'),'show approved parameter dependency');
    assert(text(target).includes('依赖：create'));assert(text(target).includes('创建 <script> 工单'));
    await buttons.find(button=>button.textContent===(approve?'确认整个方案':'拒绝')).onclick();
    assert.equal(requests.length,1);assert.equal(requests[0].url,'/api/agent-runs/run/plans/plan%2F1/decision');
    const body=JSON.parse(requests[0].options.body);assert.deepEqual(body,{fingerprint:'frozen-fingerprint',approve});
    assert.equal(requests[0].options.headers['X-Agent-Action'],'1');assert.equal(requests[0].options.credentials,'same-origin');
    assert.equal(target.querySelectorAll('button').length,0,'decision replaces confirmation controls');
  }
  console.log('PASS: whole-plan approve/reject, parameter references, bound-call deduplication, safe text rendering');
})().catch(error=>{console.error(error);process.exitCode=1;});
