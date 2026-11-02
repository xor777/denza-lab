"""Offline tests for the parked CPU comparison's reader and summary."""
import subprocess
import unittest

import perf_ab


def pstat(ppid, name, pid, comm, utime, stime, cutime, cstime):
    return (f'PSTAT {ppid} {name} {pid} ({comm}) S {ppid} 1 1 0 -1 4194304 0 0 0 0 '
            f'{utime} {stime} {cutime} {cstime} 20 0 1 0 0 0 0')


RAW = '\n'.join([
    'CLK_TCK 100',
    'SAMPLE 1000.00 500.00',
    'cpu  1000 0 500 8000 0 100 0 0 0 0',
    'processes 10000',
    'MemAvailable:    4000000 kB',
    'THERMAL 94000',
    pstat(1, 'dev.denza.apps', 28907, 'dev.denza.apps', 100, 50, 0, 0),
    pstat(1, 'com.byd.avc', 4926, 'TS_loadApaModel', 10, 10, 0, 0),
    pstat(2556, 'sh', 30583, 'sh', 0, 0, 1000, 2000),
    pstat(2556, 'denza_vehicle_read', 31000, 'denza_vehicle_r', 5, 5, 0, 0),
    'ADBD 2556',
    'SAMPLE 1010.00 505.00',
    'cpu  1400 0 800 8200 0 200 0 0 0 0',
    'processes 10700',
    'MemAvailable:    3900000 kB',
    pstat(1, 'dev.denza.apps', 28907, 'dev.denza.apps', 300, 150, 0, 0),
    pstat(1, 'com.byd.avc', 4926, 'TS_loadApaModel', 60, 10, 0, 0),
    pstat(2556, 'sh', 30583, 'sh', 0, 0, 1100, 2165),
    pstat(2556, 'denza_vehicle_read', 31000, 'denza_vehicle_r', 15, 15, 0, 0),
    'ADBD 2556',
    'END',
])


class PerfAbTests(unittest.TestCase):
    def test_a_run_is_summarised_per_process_by_its_ps_name(self):
        clk, samples = perf_ab.parse(RAW)
        self.assertEqual(clk, 100)
        self.assertEqual(len(samples), 2)
        summary = perf_ab.summarize(clk, samples)

        # 1000 ticks in the interval: 200 idle, 800 busy, 300 + 100 of it the kernel's.
        self.assertEqual(summary['cpu_busy_percent']['median'], 80.0)
        self.assertEqual(summary['kernel_irq_percent']['median'], 40.0)
        self.assertEqual(summary['processes_per_second']['median'], 70.0)
        self.assertEqual(summary['mem_available_mib']['median'], round(3900000 / 1024, 2))
        per = summary['process_one_core_percent']
        # 300 ticks over 10 s at 100 a second: 30% of one core.
        self.assertEqual(per['dev.denza.apps']['mean'], 30.0)
        # AVC's thread name in /proc is not its name.
        self.assertEqual(per['com.byd.avc']['mean'], 5.0)
        # The shells and the helper together, waited-for children included; the helper alone too.
        self.assertEqual(per['adbd children']['mean'], 26.5 + 2.0)
        self.assertEqual(per['denza_vehicle_read']['mean'], 2.0)

    def test_a_process_that_restarted_is_not_counted_across_its_restart(self):
        restarted = RAW.replace(
            pstat(1, 'dev.denza.apps', 28907, 'dev.denza.apps', 300, 150, 0, 0),
            pstat(1, 'dev.denza.apps', 29999, 'dev.denza.apps', 3, 1, 0, 0))
        clk, samples = perf_ab.parse(restarted)
        self.assertNotIn('dev.denza.apps', perf_ab.summarize(clk, samples)['process_one_core_percent'])

    def test_the_sampler_is_valid_shell(self):
        script = perf_ab.sampler(2, 5)
        self.assertNotIn('COUNT', script)
        self.assertIn('ru.yandex.yandexnavi|', script)
        subprocess.run(['sh', '-n'], input=script.encode(), check=True)


if __name__ == '__main__':
    unittest.main()
