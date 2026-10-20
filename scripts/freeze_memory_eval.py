"""Pre-inference target/gold consistency gate; deliberately not a semantic judge."""
import json,sys,hashlib,datetime
from pathlib import Path

FIELDS=('target_proposition','asked_attribute','time_scope','judge_layer')
LAYERS={'WORLD_CLAIM','SOURCE_STATEMENT'}
LABELS={'AFFIRMED','DENIED','UNCERTAIN','NONE'}

def validate(inputs,gold):
    cases={}
    for case in inputs['cases']:
        assert case['id'] not in cases,'duplicate_case'
        units={}
        for unit in case['questions']:
            assert unit['id'] not in units,'duplicate_question'
            assert isinstance(unit.get('question'),str) and unit['question'].strip(),'missing_question'
            for field in FIELDS:
                assert isinstance(unit.get(field),str) and unit[field].strip(),'missing_'+field
            assert unit['judge_layer'] in LAYERS,'invalid_layer'
            units[unit['id']]=unit
        assert units,'empty_case'
        cases[case['id']]=units
    assert len(gold)==len(cases),'gold_case_count'
    seen=set()
    for case in gold:
        assert case['id'] in cases and case['id'] not in seen,'gold_case_identity'
        seen.add(case['id']);units=cases[case['id']];ids=set()
        for unit in case['questions']:
            assert unit['id'] in units and unit['id'] not in ids,'gold_question_identity'
            ids.add(unit['id'])
            for field in FIELDS:assert unit.get(field)==units[unit['id']][field],'gold_target_mismatch_'+field
            assert unit.get('support') in LABELS,'invalid_label'
            assert isinstance(unit.get('gold'),str) and unit['gold'].strip(),'missing_gold'
        assert ids==set(units),'gold_question_count'
    return True

def freeze(inputs_path,gold_path,candidate_path,output_path):
    paths=[Path(p) for p in (inputs_path,gold_path,candidate_path)]
    raw=[p.read_bytes() for p in paths]
    inputs,gold,candidate=[json.loads(value) for value in raw]
    validate(inputs,gold)
    assert isinstance(candidate.get('model'),str) and candidate['model'].strip(),'missing_model'
    assert isinstance(candidate.get('parameters'),dict) and candidate['parameters'],'missing_parameters'
    result=dict(frozenAt=datetime.datetime.now().astimezone().isoformat(),
        files={str(p.resolve()):hashlib.sha256(value).hexdigest() for p,value in zip(paths,raw)},
        notice='Structure only; independently review target semantics before inference. Do not upload gold.')
    with Path(output_path).open('x',encoding='utf8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    return result

if __name__=='__main__':
    if len(sys.argv)!=5:raise SystemExit('usage: freeze_memory_eval.py inputs.json gold.json candidate.json NEW_MANIFEST.json')
    freeze(*sys.argv[1:])
    print('frozen; semantic review still required; gold stays outside model inputs')
