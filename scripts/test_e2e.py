import re
import unittest

import e2e

PHASE = e2e.Phase('p', ('BLOCK_STASIS_SMOKE_PASSED', 'SIGN_STASIS_SMOKE_PASSED'), ('BLOCK_STASIS_SMOKE_FAILED',), '_SMOKE_')


class EvaluateTest(unittest.TestCase):
    def test_all_markers_and_clean_exit_pass(self):
        verdict = e2e.evaluate(['[INFO] BLOCK_STASIS_SMOKE_PASSED', '[INFO] SIGN_STASIS_SMOKE_PASSED: 52'], PHASE, 0)
        self.assertEqual(verdict['status'], 'PASS')
        self.assertIsNone(verdict['reason'])

    def test_marker_must_match_whole_token(self):
        verdict = e2e.evaluate(['FRAGILE_BLOCK_STASIS_SMOKE_PASSED', 'SIGN_STASIS_SMOKE_PASSED'], PHASE, 0)
        self.assertEqual(verdict['missing'], ['BLOCK_STASIS_SMOKE_PASSED'])
        self.assertIn('missing markers: BLOCK_STASIS_SMOKE_PASSED', verdict['reason'])

    def test_failure_marker_wins_and_keeps_stack_trace(self):
        lines = ['BLOCK_STASIS_SMOKE_PASSED', '[SEVERE] BLOCK_STASIS_SMOKE_FAILED', 'java.lang.IllegalStateException: x',
                 '\tat Foo.bar(Foo.java:1)', 'SIGN_STASIS_SMOKE_PASSED']
        verdict = e2e.evaluate(lines, PHASE, 0)
        self.assertEqual(verdict['status'], 'FAIL')
        self.assertIn('IllegalStateException', verdict['reason'])

    def test_generic_plugin_load_error_fails(self):
        lines = ['Error occurred while enabling BakeMechanisms v0.1.0', 'BLOCK_STASIS_SMOKE_PASSED', 'SIGN_STASIS_SMOKE_PASSED']
        self.assertEqual(e2e.evaluate(lines, PHASE, 0)['status'], 'FAIL')

    def test_nonzero_exit_and_watchdog_fail(self):
        lines = ['BLOCK_STASIS_SMOKE_PASSED', 'SIGN_STASIS_SMOKE_PASSED']
        self.assertEqual(e2e.evaluate(lines, PHASE, 1)['reason'], 'server exit code 1')
        self.assertEqual(e2e.evaluate(lines, PHASE, 0, 'watchdog: x')['reason'], 'watchdog: x')

    def test_review_phase_ids_are_prefixed(self):
        phase = e2e.review_phase('r', ('C10',))
        verdict = e2e.evaluate(['[INFO] [BakeCorrectness] REVIEW_PASS C10 tick=12 restart'], phase, 0)
        self.assertEqual(verdict['status'], 'PASS')
        self.assertEqual(e2e.evaluate(['REVIEW_PASS C10_X tick=1'], phase, 0)['status'], 'FAIL')


def literals(directory, pattern):
    found = set()
    for source in (e2e.ROOT / 'src' / directory).rglob('*.java'):
        found.update(re.findall(pattern, source.read_text(encoding='utf-8')))
    return found


def expected(*suites):
    return {m for s in e2e.SUITES if s.name in suites for p in s.phases for m in p.expected}


class MarkerDriftTest(unittest.TestCase):
    """A check added to a test plugin must also be required by the orchestrator, or it can silently vanish."""

    def test_every_smoke_marker_is_required(self):
        markers = literals('smokeTest', r'"([A-Z_]+_SMOKE_PASSED)') | literals('smokeTest', r'"(ENTITY_NBT_[A-Z_]+_PASSED)')
        self.assertTrue(markers)
        self.assertEqual(markers - expected('stasis-smoke', 'entity-nbt'), set())

    def test_every_review_check_is_required(self):
        checks = literals('correctness', r'pass\("([A-Z0-9_]+)"') - {'ACCOUNTING'}
        self.assertTrue(checks)
        required = {m.removeprefix('REVIEW_PASS ') for m in expected('correctness', 'short-profile')}
        self.assertEqual(checks - required, set())


    def test_coverage_matrix_names_only_required_markers(self):
        every = {m for s in e2e.SUITES for p in s.phases for m in p.expected}
        for name, markers, _ in e2e.COVERAGE:
            self.assertTrue(markers, name)
            self.assertEqual(set(markers) - every, set(), name)


class CoverageTest(unittest.TestCase):
    def test_partial_run_reports_unconfirmed_markers(self):
        report = {'suites': [{'phases': [{'passed': ['REVIEW_PASS C11']}]}]}
        rows = {name: status for name, status, _ in e2e.coverage(report)}
        self.assertEqual(rows['Повторное профилирование после восстановления и безопасное удаление'], 'PASS')
        self.assertTrue(all(status != 'PASS' for name, status in rows.items() if 'Повторное' not in name))



class ChatTextTest(unittest.TestCase):
    def setUp(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('clients', e2e.ROOT / 'scripts' / 'correctness-clients.py')
        self.clients = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.clients)

    @staticmethod
    def string(value):
        data = value.encode('utf-8')
        return len(data).to_bytes(2, 'big') + data

    def test_plain_string_component(self):
        self.assertEqual(self.clients.chat_text(b'\x08' + self.string('Профиль отклонён') + b'\x00'), 'Профиль отклонён')

    def test_compound_with_extra_and_style(self):
        child = b'\x08' + self.string('text') + self.string(' READY.') + b'\x00'
        payload = (b'\x0a' + b'\x08' + self.string('color') + self.string('red')
                   + b'\x08' + self.string('text') + self.string('Ферма')
                   + b'\x09' + self.string('extra') + b'\x0a' + (1).to_bytes(4, 'big') + child
                   + b'\x01' + self.string('bold') + b'\x01' + b'\x00' + b'\x00')
        self.assertEqual(self.clients.chat_text(payload), 'Ферма READY.')


if __name__ == '__main__':
    unittest.main()
