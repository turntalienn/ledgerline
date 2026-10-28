import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPORT = Path(__file__).resolve().parents[1] / 'benchmark-report.py'


class BenchmarkReportTest(unittest.TestCase):
    def extract(self, balance, accepted=2):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        directory = Path(temp.name)
        log = directory / 'k6.log'
        output = directory / 'result.json'
        log.write_text(f'console: merchant 3: balance={balance} journalBalance={balance}\n'
                       + 'LEDGERLINE_RESULT=' + json.dumps({'accepted': accepted}) + '\n')
        run = subprocess.run([sys.executable, str(REPORT), str(log), str(output), 'row-lock'],
                             capture_output=True, text=True)
        return run, json.loads(output.read_text())

    def test_matches_acknowledged_transfers(self):
        run, result = self.extract(200)
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue(result['balanceMatchesAccepted'])

    def test_rejects_duplicate_posting_even_if_caches_agree(self):
        run, result = self.extract(300)
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(result['balanceMatchesAccepted'])

    def test_missing_summary_is_not_a_zero_throughput_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / 'k6.log'
            log.write_text('load generator crashed')
            run = subprocess.run([sys.executable, str(REPORT), str(log), str(Path(tmp) / 'out.json'), 'row-lock'],
                                 capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertIn('Expected exactly one', run.stderr)


if __name__ == '__main__':
    unittest.main()
