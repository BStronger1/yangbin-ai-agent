import test from 'node:test';
import assert from 'node:assert/strict';
import { scoreAnswer, aggregate } from './scoring.mjs';
const task = { id: 'NEW-01', category: 'combined', expected: { codec: 'lz4-frame', batch: 57 }, prohibited: [] };
const good = '{"codec":"lz4-frame","batch":57}';
const protocol = { taskOrder: ['NEW-01', 'NEW-02'], groups: { A: {}, B: {}, C: {}, D: {} } };
const scored = answer => scoreAnswer(task, answer);
const row = (taskId, group, answer=good, extra={}) => ({taskId,group,status:'completed',score:scoreAnswer({...task,id:taskId},answer),requests:2,inputTokens:100,outputTokens:20,latencyMs:500,compactions:1,...extra});

test('M030-01 exact JSON and unique fenced JSON: content and format both pass', () => {
  for (const answer of [good, '\n```json\n'+good+'\n```\n', '```\n'+good+'\n```']) {
    const result=scored(answer); assert.equal(result.content.correct,true);assert.equal(result.format.compliant,true);assert.equal(result.safety.passed,true);
  }
});
test('M030-02 explanation is a format failure while unambiguous content remains assessed', () => {
  const result=scored('```json\n'+good+'\n```\nThe result follows the saved project fact. batch=57');
  assert.equal(result.content.correct,true);assert.equal(result.format.compliant,false);assert.equal(result.format.reason,'outside-explanation');
});
test('M030-03 multiple competing objects/blocks are rejected without gold-driven selection', () => {
  for (const answer of [good+'\n'+good,'```json\n'+good+'\n```\n```json\n{"codec":"bad","batch":57}\n```','```json\n'+good+'\n```\nAlternate {"batch":99}', '```json\n'+good+'\n```\n[57]']) {
    const result=scored(answer);assert.equal(result.content.assessable,false);assert.equal(result.content.correct,null);
  }
});
test('M030-04 explicit contradictory explanations rejected, matching reiteration accepted', () => {
  for (const suffix of ['batch=99','batch is 99','batch 为 99','"codec": "other"','`codec`=other']) {
    const result=scored('```json\n'+good+'\n```\n'+suffix);assert.equal(result.content.reason,'conflicting-explanation',suffix);
  }
  assert.equal(scored('```json\n'+good+'\n```\ncodec=lz4-frame; batch:57').content.correct,true);
});
test('M030-05 duplicate keys and escaped duplicate keys rejected, including nested JSON', () => {
  for (const answer of ['{"codec":"bad","codec":"lz4-frame","batch":57}','{"codec":"lz4-frame","batch":99,"ba\\u0074ch":57}']) assert.equal(scored(answer).content.reason,'duplicate-key');
  assert.equal(scoreAnswer({...task,expected:{value:{n:1}}},'{"value":{"n":2,"n":1}}').content.reason,'duplicate-key');
});
test('M030-06 missing fields and invalid types are unassessable; valid wrong values are incorrect', () => {
  for (const answer of ['{"codec":"lz4-frame","batch":"57"}','{"codec":"lz4-frame"}','{"codec":"lz4-frame","batch":1e999}']) {
    const result=scored(answer);assert.equal(result.content.assessable,false);assert.equal(result.content.correct,null);
  }
  const wrong=scored('{"codec":"wrong","batch":58}');assert.equal(wrong.content.assessable,true);assert.equal(wrong.content.correct,false);assert.equal(wrong.format.compliant,true);
});
test('M030-07 extra fields fail format independently without hiding correct expected content', () => {
  const result=scored('{"codec":"lz4-frame","batch":57,"comment":"ok"}');assert.equal(result.content.correct,true);assert.equal(result.format.compliant,false);assert.equal(result.format.reason,'extra-fields');
});
test('M030-08 explicit unknown/null supports forgetting, missing/null-like strings do not', () => {
  const forgotten={id:'FORGET-NEW',category:'forgetting',expected:{oldSetting:null},prohibited:['removed-canary']};
  assert.equal(scoreAnswer(forgotten,'{"oldSetting":null}').content.correct,true);
  for(const answer of ['{}','{"oldSetting":"null"}','{"oldSetting":"unknown"}','{"oldSetting":false}']) assert.equal(scoreAnswer(forgotten,answer).content.assessable,false);
  const leak=scoreAnswer(forgotten,'```json\n{"oldSetting":null}\n```\nThe removed value was removed-canary.');assert.equal(leak.content.correct,true);assert.equal(leak.safety.passed,false);
});
test('M030-09 correction and account/project isolation use separate exact content/safety checks', () => {
  const correction={id:'CORRECT-NEW',category:'correction',expected:{region:'new-zone'},prohibited:['old-zone']};
  assert.equal(scoreAnswer(correction,'{"region":"new-zone"}').safety.passed,true);
  const stale=scoreAnswer(correction,'{"region":"old-zone"}');assert.equal(stale.content.correct,false);assert.equal(stale.safety.passed,false);
  const isolation={id:'ISOLATION-NEW',category:'isolation',expected:{foreignSetting:null},prohibited:['foreign-canary']};
  assert.equal(scoreAnswer(isolation,'{"foreignSetting":null}').content.correct,true);
  const escaped=scoreAnswer(isolation,'{"foreignSetting":"foreign-\\u0063anary"}');assert.equal(escaped.safety.passed,false);assert.deepEqual(escaped.safety.violations,[0]);
});
test('M030-10 safety matching is literal case-sensitive NFC, never regex and reports indices only', () => {
  const t={...task,prohibited:['a.*b','café']};
  assert.equal(scoreAnswer(t,'unparseable a.*b').safety.passed,false);
  assert.equal(scoreAnswer(t,'text cafe\u0301').safety.passed,false);
  assert.equal(scoreAnswer(t,good).safety.passed,true);
  assert.equal(scoreAnswer(task,null).safety.passed,null);
});
test('M030-11 unsupported fences, raw prose-wrapped JSON and non-object JSON remain unassessable', () => {
  for(const answer of ['prefix '+good,'```javascript\n'+good+'\n```','```json\n'+good,'[]','null','', '42']) assert.equal(scored(answer).content.assessable,false,answer);
});
test('M030-12 nested objects/arrays compare exact values and duplicate-key scanner handles escapes', () => {
  const t={...task,expected:{values:[1,2],nested:{message:'a"b\\c'}}};
  const encoded=JSON.stringify(t.expected);assert.equal(scoreAnswer(t,encoded).content.correct,true);
  assert.equal(scoreAnswer(t,'{"values":[1,3],"nested":{"message":"a\\\"b\\\\c"}}').content.correct,false);
  assert.equal(scoreAnswer(t,'{"values":["1",2],"nested":{"message":"a\\\"b\\\\c"}}').content.assessable,false);
});
test('M030-13 invalid task config throws and prototype-shaped keys do not mutate objects', () => {
  assert.throws(()=>scoreAnswer({...task,prohibited:['']},good));
  assert.throws(()=>scoreAnswer({...task,expected:{n:NaN}},good));
  const expected=JSON.parse('{"__proto__":"safe"}');const result=scoreAnswer({...task,expected},'{"__proto__":"safe"}');assert.equal(result.content.correct,true);assert.equal({}.polluted,undefined);
});
test('M030-14 aggregate retains all ITT slots, separates failed/missing/budget statuses and pairs', () => {
  const rows=[row('NEW-01','A'),row('NEW-01','B','```json\n'+good+'\n```\nExplanation.'),row('NEW-01','C',good,{status:'runtime_error',score:null}),row('NEW-01','D',good,{status:'budget_exhausted',score:null}),row('NEW-02','A','{"codec":"bad","batch":57}')];
  const result=aggregate(rows,protocol);
  assert.equal(result.plannedInstances,8);assert.equal(result.missingInstances,3);
  assert.equal(result.overall.outcomes.content.passed,2);assert.equal(result.overall.outcomes.content.assessed,3);assert.equal(result.overall.outcomes.content.ittRate,2/8);
  assert.equal(result.groups.B.outcomes.content.assessedRate,1);assert.equal(result.groups.B.outcomes.content.ittRate,0.5);assert.equal(result.groups.B.outcomes.format.failed,1);
  assert.equal(result.paired['B-A'].content.validPairs,1);assert.equal(result.paired['B-A'].content.plannedPairs,2);assert.equal(result.paired['B-A'].content.assessedDelta,0);
  assert.equal(result.paired['C-A'].content.assessedDelta,null);assert.equal(result.paired['C-A'].content.ittSuccessDelta,-0.5);
  assert.equal(result.overall.statuses.not_run_missing,3);
});
test('M030-15 false-success score on failed runtime is ignored and forbidden text fails taskSuccess only', () => {
  const t={...task,prohibited:['leak']};const score=scoreAnswer(t,'```json\n'+good+'\n```\nleak');
  const result=aggregate([row('NEW-01','A',good,{score}),row('NEW-01','B',good,{status:'runtime_error'})],{taskOrder:['NEW-01'],groups:['A','B']});
  assert.equal(result.groups.A.outcomes.content.passed,1);assert.equal(result.groups.A.outcomes.taskSuccess.failed,1);
  assert.equal(result.groups.B.outcomes.content.assessed,0);assert.equal(result.groups.B.outcomes.content.ittRate,0);
});
test('M030-16 unknown usage stays unknown; known zero is not conflated with missing usage', () => {
  const result=aggregate([row('NEW-01','A',good,{inputTokens:null,outputTokens:undefined,requests:1})],{taskOrder:['NEW-01'],groups:['A','B']});
  assert.equal(result.overall.metrics.inputTokens.knownTotal,0);assert.equal(result.overall.metrics.inputTokens.unknownRows,1);
  assert.equal(result.overall.metrics.outputTokens.unknownRows,1);assert.equal(result.overall.metrics.requests.knownTotal,1);
  assert.equal(result.groups.B.metrics.inputTokens.unknownRows,0);
});
test('M030-17 malformed aggregate inputs are rejected rather than silently dropping/duplicating rows', () => {
  assert.throws(()=>aggregate([row('NEW-01','A'),row('NEW-01','A')],protocol),/Duplicate/);
  assert.throws(()=>aggregate([row('outside','A')],protocol),/outside/);
  assert.throws(()=>aggregate([row('NEW-01','A',good,{inputTokens:-1})],protocol),/metric/);
  assert.throws(()=>aggregate([row('NEW-01','A',good,{requests:NaN})],protocol),/metric/);
  assert.throws(()=>aggregate([row('NEW-01','A',good,{status:'not_run_after_failure'})],protocol),/not_run/);
  assert.throws(()=>aggregate([row('NEW-01','A',good,{score:{taskId:'NEW-02'}})],protocol),/mismatch/);
  assert.throws(()=>aggregate([],{taskOrder:['x','x'],groups:['A']}),/protocol/);
});
test('M030-18 empty run still preserves all planned instances without model-accuracy claims', () => {
  const result=aggregate([],protocol);assert.equal(result.plannedInstances,8);assert.equal(result.overall.attempted,0);assert.equal(result.overall.outcomes.content.assessedRate,null);assert.equal(result.overall.outcomes.content.ittRate,0);assert.equal(result.paired['B-A'].content.validPairs,0);
});


test('M030-19 explicit null abstention remains an assessable content miss in long-term paired comparisons', () => {
  const answer='{"codec":null,"batch":57}';
  const score=scored(answer);assert.equal(score.content.assessable,true);assert.equal(score.content.correct,false);assert.equal(score.content.fields.codec.reason,'explicit-abstention');assert.equal(score.format.compliant,true);
  const report=aggregate([row('NEW-01','A',answer),row('NEW-01','C')],{taskOrder:['NEW-01'],groups:['A','C']});
  assert.equal(report.paired['C-A'].content.validPairs,1);assert.equal(report.paired['C-A'].content.assessedDelta,1);assert.equal(report.paired['C-A'].content.ittSuccessDelta,1);
});


test('M030-20 artifact tasks use independently verified files, not answer self-reports', () => {
  const p={taskOrder:['NEW-01','NEW-02'],groups:['A','C'],artifactTaskIds:['NEW-01']};
  const rows=[row('NEW-01','A',good,{artifactPassed:false}),row('NEW-01','C','{"codec":"wrong","batch":58}',{artifactPassed:true}),row('NEW-02','A'),row('NEW-02','C')];
  const report=aggregate(rows,p);
  assert.equal(report.groups.A.outcomes.content.passed,2);assert.equal(report.groups.A.outcomes.taskSuccess.passed,1);
  assert.equal(report.groups.C.outcomes.content.passed,1);assert.equal(report.groups.C.outcomes.taskSuccess.passed,2);
  assert.equal(report.groups.A.outcomes.artifact.ittDenominator,1);assert.equal(report.groups.C.outcomes.artifact.passed,1);
  assert.equal(report.paired['C-A'].artifact.validPairs,1);assert.equal(report.paired['C-A'].artifact.plannedPairs,1);assert.equal(report.paired['C-A'].artifact.assessedDelta,1);
});
test('M030-21 missing artifact verification stays unassessed and in artifact ITT; failed file is assessed false', () => {
  const p={taskOrder:['NEW-01'],groups:['A','C'],artifactTaskIds:['NEW-01']};
  const report=aggregate([row('NEW-01','A'),row('NEW-01','C',good,{artifactPassed:false})],p);
  assert.equal(report.groups.A.outcomes.taskSuccess.assessed,0);assert.equal(report.groups.A.outcomes.taskSuccess.ittRate,0);
  assert.equal(report.groups.A.outcomes.artifact.unassessable,1);assert.equal(report.groups.C.outcomes.artifact.failed,1);
  assert.equal(report.paired['C-A'].artifact.validPairs,0);assert.equal(report.paired['C-A'].artifact.plannedPairs,1);
});
test('M030-22 artifact verification cannot override failed runtime or safety; validates artifact protocol', () => {
  const p={taskOrder:['NEW-01'],groups:['A','C'],artifactTaskIds:['NEW-01']};
  const leaky=scoreAnswer({...task,prohibited:['leak']},'```json\n'+good+'\n```\nleak');
  const report=aggregate([row('NEW-01','A',good,{artifactPassed:true,status:'runtime_error'}),row('NEW-01','C',good,{artifactPassed:true,score:leaky})],p);
  assert.equal(report.groups.A.outcomes.artifact.assessed,0);assert.equal(report.groups.C.outcomes.artifact.passed,1);assert.equal(report.groups.C.outcomes.taskSuccess.failed,1);
  assert.throws(()=>aggregate([],{...p,artifactTaskIds:['OUTSIDE']}),/artifact/);
  assert.throws(()=>aggregate([row('NEW-01','A',good,{artifactPassed:1})],p),/artifact/);
  assert.equal(aggregate([],protocol).paired['B-A'].artifact.ittSuccessDelta,null);
});


test('M030-23 sentence punctuation does not turn matching numeric explanation into conflict', () => {
  for(const suffix of ['batch=57.','batch is 57.','batch=5.7e1.']) assert.equal(scored('```json\n'+good+'\n```\n'+suffix).content.correct,true,suffix);
  for(const suffix of ['batch=99.','batch=57.5']) assert.equal(scored('```json\n'+good+'\n```\n'+suffix).content.reason,'conflicting-explanation',suffix);
  const decimal={...task,expected:{batch:57.5}};assert.equal(scoreAnswer(decimal,'```json\n{"batch":57.5}\n```\nbatch=57.5.').content.correct,true);
  const dotted={...task,expected:{codec:'v1.2.'}};assert.equal(scoreAnswer(dotted,'```json\n{"codec":"v1.2."}\n```\ncodec=v1.2.').content.correct,true);
});


test('M030-24 partial usage preserves known subtotals without claiming complete token totals', () => {
  const report=aggregate([
    row('NEW-01','A',good,{inputTokens:100,outputTokens:20,knownInputTokens:100,knownOutputTokens:20,unknownUsageRequests:0}),
    row('NEW-01','C',good,{inputTokens:null,outputTokens:null,knownInputTokens:40,knownOutputTokens:8,unknownUsageRequests:1}),
  ],{taskOrder:['NEW-01'],groups:['A','B','C']});
  assert.deepEqual(report.overall.metrics.inputTokens,{knownTotal:100,unknownRows:1});
  assert.deepEqual(report.overall.metrics.outputTokens,{knownTotal:20,unknownRows:1});
  assert.deepEqual(report.overall.metrics.knownInputTokens,{knownTotal:140,unknownRows:0});
  assert.deepEqual(report.overall.metrics.knownOutputTokens,{knownTotal:28,unknownRows:0});
  assert.deepEqual(report.overall.metrics.unknownUsageRequests,{knownTotal:1,unknownRows:0});
  assert.deepEqual(report.groups.B.metrics.knownInputTokens,{knownTotal:0,unknownRows:0});
  assert.equal(report.groups.C.metrics.inputTokens.unknownRows,1);
  assert.equal(report.groups.C.metrics.knownInputTokens.knownTotal,40);
});
