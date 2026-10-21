"""Local manifest and output reconciliation; all reply fixtures are synthetic."""
import unittest
import child_identity_probe as probe


def fixture():
    lines=['REGISTERED','START steps=8 passive_ms=2000 request_ms=4000 overall_ms=50000']
    for row in probe.fixed_plan():
        i=row['step'];t=(2+i)*5_000_000_000
        lines += [f"REQUEST step={i} ecu={row['ecuId']} t_ns={t} raw=0A{row['requestIdHex'].zfill(4)}03{row['udsHex']}",
                  f'TRANSPORT_RESULT step={i} result=0',
                  f"FRAME step={i} t_ns={t+1} raw=0A{row['responseIdHex'].zfill(4)}0C01010162{row['udsHex'][2:]}{row['cachedSoftwareCode']}",
                  f'STEP_END step={i} state=CACHE_MATCH']
    return '\n'+'\n'.join(lines+['UNREGISTERED','END reason=PLAN_DONE requests=8 completed=8 cleanup=true callbacks=16 queued=0'])+'\n'


class OutputTest(unittest.TestCase):
    def test_fixed_eight_and_strict_decode(self):
        rows=probe.validate_output(fixture())
        self.assertEqual(len(rows),8)
        self.assertTrue(all(r['frames'][-1]['matchesCachedCode'] for r in rows))

    def test_partial_unregistered_or_missing_end(self):
        for s in [fixture().replace('UNREGISTERED\n',''), fixture().split('END reason=')[0],fixture().replace('completed=8','completed=7')]:
            with self.subTest(text=s[-90:]),self.assertRaises(ValueError):probe.validate_output(s)

    def test_wrong_request_did_address_or_order(self):
        for a,b in [('raw=0A07500322F8C4','raw=0A07500322F194'),('raw=0A0758','raw=0A0759'),
                    ('STEP_END step=1','STEP_END step=0'),('result=0','result=-1'),('62F8C4','62F8C5')]:
            with self.subTest(mutation=a),self.assertRaises(ValueError):probe.validate_output(fixture().replace(a,b,1))

    def test_late_or_false_cache_match(self):
        for a,b in [('t_ns=10000000001','t_ns=14000000000'),('47380011DFE3000102','47380011DFE3000103'),
                    ('47380011DFE3000102','000000000000000000')]:
            with self.subTest(mutation=a),self.assertRaises(ValueError):probe.validate_output(fixture().replace(a,b,1))


if __name__=='__main__':unittest.main()
