(function (root) {
  'use strict';
  const labels = {READY:'等待执行', RUNNING:'正在执行', WAITING:'等待外部结果', CONFIRMATION_REQUIRED:'等待确认', UNKNOWN:'提交结果待核实', SUCCEEDED:'已完成', FAILED:'执行失败', REJECTED:'已拒绝', EXPIRED:'已过期', PENDING:'等待方案确认', APPROVED:'方案已批准', SUPERSEDED:'方案已废弃', NOT_STARTED:'未开始', PARTIAL:'部分完成'};
  function el(tag, text, parent) {const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(parent)parent.appendChild(node);return node;}
  async function request(path, body) {
    const options={credentials:'same-origin',cache:'no-store'};
    if(body){options.method='POST';options.headers={'Content-Type':'application/json','X-Agent-Action':'1'};options.body=JSON.stringify(body);}
    const response=await fetch('/api/agent-runs/'+path,options);const data=await response.json();
    if(!response.ok)throw new Error(data.error||'读取任务失败');return data;
  }
  function render(target, payload, onComplete) {
    if(!payload||!payload.runId||payload.execution_status!=='WAITING')return;
    const panel=el('section',undefined,target);panel.className='monitor-confirmation';let timer, busy=false;
    const path=encodeURIComponent(payload.runId);
    function decisionButtons(box,kind,id,fingerprint,confirmLabel){
      for(const [text,approve] of [[confirmLabel,true],['拒绝',false]]){
        const button=el('button',text,box);button.type='button';button.disabled=busy;
        button.onclick=async()=>{if(busy)return;busy=true;panel.querySelectorAll('button').forEach(b=>b.disabled=true);
          try{show(await request(path+'/'+kind+'/'+encodeURIComponent(id)+'/decision',{fingerprint,approve}));}
          catch(error){el('p',error.message,panel);}finally{busy=false;panel.querySelectorAll('button').forEach(b=>b.disabled=false);}};
      }
    }
    function show(view) {
      panel.replaceChildren();el('strong',view.status==='WAITING'&&view.waitTarget&&view.waitTarget.kind==='PLAN_APPROVAL'?'等待方案确认':labels[view.status]||view.status,panel);
      for(const plan of view.plans||[]){
        const box=el('div',undefined,panel);
        el('h4',plan.summary||'待执行方案',box);
        el('p','审批：'+(labels[plan.approvalStatus]||plan.approvalStatus)+'；执行：'+(labels[plan.executionStatus]||plan.executionStatus),box);
        el('p','任一步失败即停止，已完成的操作不会自动回滚。',box);
        for(const call of plan.calls||[]){
          const step=el('div',undefined,box);el('strong',call.id+' · '+(call.summary||call.tool),step);
          el('p',labels[call.status]||call.status,step);el('pre',JSON.stringify(call.arguments,null,2),step);
          if((call.dependsOn||[]).length)el('p','依赖：'+call.dependsOn.join('、'),step);
          if(Object.keys(call.resourceVersions||{}).length)el('pre','目标版本：'+JSON.stringify(call.resourceVersions,null,2),step);
          if(call.resolvedArguments)el('pre','实际参数：'+JSON.stringify(call.resolvedArguments,null,2),step);
          if(call.result)el('pre','执行结果：'+JSON.stringify(call.result,null,2),step);
        }
        if(plan.approvalStatus==='PENDING'&&!view.result){
          el('p','确认有效期至 '+new Date(plan.expiresAt).toLocaleString(),box);
          decisionButtons(box,'plans',plan.planId,plan.fingerprint,'确认整个方案');
        }
      }
      for(const call of view.calls||[]){
        if(call.preparedPlanId)continue;
        const box=el('div',undefined,panel);el('p',call.summary||call.tool,box);el('p',labels[call.status]||call.status,box);
        el('pre',JSON.stringify(call.arguments,null,2),box);
        if(Object.keys(call.resourceVersions||{}).length)el('pre','目标版本：'+JSON.stringify(call.resourceVersions,null,2),box);
        if(call.status==='CONFIRMATION_REQUIRED'&&!view.result){
          el('p','确认有效期至 '+new Date(call.expiresAt).toLocaleString(),box);
          decisionButtons(box,'calls',call.invocationId,call.fingerprint,'确认执行');
        }
      }
      if(view.result){
        el('p',view.result.answer,panel);
        if(!view.businessCommitted){el('p','正在保存结果…',panel);return false;}
        if(root.MonitorConfirmation)root.MonitorConfirmation.render(panel,view.result.agentPayload);
        if(onComplete)onComplete(view.result,view.turnId);
        return true;
      }return false;
    }
    async function refresh(){if(!panel.isConnected)return;try{if(show(await request(path)))return;}catch(error){el('p',error.message,panel);}timer=setTimeout(refresh,3000);}
    show(payload);timer=setTimeout(refresh,1000);
  }
  root.AgentRun={render};
})(window);
