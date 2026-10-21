import unittest

from washer_warning_event_probe import parse_log


def record(body):
    return '\n'.join('[WasherWarningEventProbe] '+line for line in body)


def sample(events=()):
    return record([
        'START uid=2000 duration_s=10',
        'GET phase=before_register t_ns=10 value=0 hex=0x0',
        'REGISTER_RETURN fid=0x26100008 t_ns=20',
        'HEARTBEAT t_ns=21',
        *events,
        'GET phase=before_unregister t_ns=100 value=0 hex=0x0',
        'UNREGISTERED',
        'DONE registered=true events='+str(len(events))+' errors=0',
    ])


class WarningLogTest(unittest.TestCase):
    def test_silence_is_not_callback_qualification(self):
        result = parse_log(sample())
        self.assertEqual(result['callbackCount'], 0)
        self.assertEqual(result['changes'], 0)
        self.assertFalse(result['realLowFluidQualified'])

    def test_values_and_changes_are_separate(self):
        result = parse_log(sample([
            'EVENT seq=1 t_ns=30 fid=0x26100008 value=319 hex=0x13F',
            'EVENT seq=2 t_ns=40 fid=0x26100008 value=319 hex=0x13F',
            'EVENT seq=3 t_ns=50 fid=0x26100008 value=0 hex=0x0',
        ]))
        self.assertEqual(result['callbackCount'], 3)
        self.assertEqual(result['changes'], 1)
        self.assertEqual(result['distinctValues'], [0, 319])

    def test_washer_callback_alone_does_not_qualify_sensor(self):
        result = parse_log(sample(['EVENT seq=1 t_ns=30 fid=0x26100008 value=477 hex=0x1DD']))
        self.assertEqual(result['distinctValues'], [477])
        self.assertFalse(result['realLowFluidQualified'])

    def test_reject_bad_capture(self):
        good = sample(['EVENT seq=1 t_ns=30 fid=0x26100008 value=319 hex=0x13F'])
        for old, new in [('seq=1','seq=2'), ('value=319','value=320'), ('errors=0','errors=1'),
                         ('UNREGISTERED','UNREGISTER_FAIL'), ('registered=true','registered=false'),
                         ('DONE registered=true events=1 errors=0',''),
                         ('phase=before_unregister','phase=before_register')]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                parse_log(good.replace(old,new))

    def test_door_control_is_not_warning_callback(self):
        text = sample().replace('[WasherWarningEventProbe] GET phase=before_unregister', record([
            'DOOR_EVENT seq=1 t_ns=30 area=1 state=1',
            'GET phase=door_callback t_ns=31 value=0 hex=0x0',
            'DOOR_EVENT seq=2 t_ns=40 area=1 state=0',
            'GET phase=door_callback t_ns=41 value=0 hex=0x0',
            'DOOR_DONE events=2',
        ])+'\n[WasherWarningEventProbe] GET phase=before_unregister')
        result = parse_log(text)
        self.assertEqual(result['callbackCount'], 0)
        self.assertEqual(result['getValues'], [0,0,0,0])
        self.assertEqual([x['state'] for x in result['doorCallbacks']], [1,0])
        for old,new in [('DOOR_DONE events=2','DOOR_DONE events=1'), ('area=1','area=9'),
                        ('GET phase=door_callback t_ns=31 value=0 hex=0x0','')]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                parse_log(text.replace(old,new))


if __name__ == '__main__':
    unittest.main()
