// Local DOM substitute: no browser, server, or model requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.isConnected = true; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  querySelectorAll() { return []; }
}

(async function () {
  const timers = [], cards = [], completions = [], requests = [];
  const result = {answer: '已完成', agentPayload: {confirmation: {ruleId: 'rule'}, clarify: [{key: 'exchange'}]}};
  let committed = false;
  const window = {MonitorConfirmation: {render: (target, payload) => cards.push(payload)}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../src/main/resources/static/agent-run.js'), 'utf8'), {
    window, document: {createElement: tag => new Element(tag)},
    setTimeout: callback => { timers.push(callback); },
    fetch: async (url, options) => {
      requests.push({url, options});
      return {ok: true, json: async () => ({status: 'SUCCEEDED', businessCommitted: committed, turnId: 'source-turn', result})};
    }
  });
  const target = new Element('main');
  window.AgentRun.render(target, {runId: 'run', execution_status: 'WAITING', status: 'WAITING'},
    (output, turn) => completions.push({output, turn}));
  await timers.shift()();
  assert.equal(cards.length, 0);
  assert.equal(completions.length, 0);
  assert.equal(timers.length, 1, 'poll until business submission finishes');
  assert(target.children[0].children.some(child => child.textContent === '正在保存结果…'));
  committed = true;
  await timers.shift()();
  assert.equal(timers.length, 0);
  assert.equal(cards.length, 1);
  assert.equal(cards[0], result.agentPayload, 'render original Monitor confirmation');
  assert.equal(completions[0].output, result, 'deliver Query clarification payload');
  assert.equal(completions[0].turn, 'source-turn');
  assert.equal(requests[0].options.credentials, 'same-origin');
  assert.equal(requests[0].options.cache, 'no-store');
  console.log('PASS: wait for business commit, render Monitor card, deliver Query clarification, stop polling');
})().catch(error => { console.error(error); process.exitCode = 1; });
