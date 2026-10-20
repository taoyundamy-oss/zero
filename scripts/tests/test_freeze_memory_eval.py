import copy,json,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from freeze_memory_eval import validate,freeze

class FreezeMemoryEvalTest(unittest.TestCase):
    def setUp(self):
        unit=dict(id='u',question='这项工作实际结束了吗？',target_proposition='这项工作实际结束',
            asked_attribute='actual_completion',time_scope='来源所述时点',judge_layer='WORLD_CLAIM')
        self.inputs=dict(cases=[dict(id='c',questions=[unit])])
        self.gold=[dict(id='c',questions=[dict(unit,support='NONE',gold='没有完成证据，不反证未完成')])]
    def test_explicit_target_passes(self):self.assertTrue(validate(self.inputs,self.gold))
    def test_natural_question_cannot_replace_explicit_fields(self):
        for field in ('target_proposition','asked_attribute','time_scope','judge_layer'):
            inputs=copy.deepcopy(self.inputs);del inputs['cases'][0]['questions'][0][field]
            with self.assertRaisesRegex(AssertionError,'missing_'+field):validate(inputs,self.gold)
    def test_gold_cannot_switch_layer(self):
        gold=copy.deepcopy(self.gold);gold[0]['questions'][0]['judge_layer']='SOURCE_STATEMENT'
        with self.assertRaisesRegex(AssertionError,'gold_target_mismatch_judge_layer'):validate(self.inputs,gold)
    def test_duplicate_cannot_inflate_denominator(self):
        inputs=copy.deepcopy(self.inputs);inputs['cases'][0]['questions']*=2
        with self.assertRaisesRegex(AssertionError,'duplicate_question'):validate(inputs,self.gold)
    def test_manifest_requires_parameters_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as root:
            paths=[Path(root)/x for x in ('inputs.json','gold.json','candidate.json','freeze.json')]
            for p,value in zip(paths,[self.inputs,self.gold,dict(model='local',parameters={})]):p.write_text(json.dumps(value),encoding='utf8')
            with self.assertRaisesRegex(AssertionError,'missing_parameters'):freeze(*paths)
            self.assertFalse(paths[3].exists())
            paths[2].write_text(json.dumps(dict(model='local',parameters=dict(temperature=0))),encoding='utf8')
            result=freeze(*paths);self.assertEqual(3,len(result['files']))
            saved=paths[3].read_bytes()
            with self.assertRaises(FileExistsError):freeze(*paths)
            self.assertEqual(saved,paths[3].read_bytes())
    def test_invalid_target_does_not_create_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            paths=[Path(root)/x for x in ('inputs.json','gold.json','candidate.json','freeze.json')]
            inputs=copy.deepcopy(self.inputs);del inputs['cases'][0]['questions'][0]['target_proposition']
            for p,value in zip(paths,[inputs,self.gold,dict(model='local',parameters=dict(temperature=0))]):p.write_text(json.dumps(value),encoding='utf8')
            with self.assertRaisesRegex(AssertionError,'missing_target_proposition'):freeze(*paths)
            self.assertFalse(paths[3].exists())

if __name__=='__main__':unittest.main()
