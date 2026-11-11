"""The process runner must reject a driver success marker accompanying a real failure."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location('correctness_review', Path(__file__).with_name('correctness-review.py'))
REVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REVIEW)


class ReviewResultTests(unittest.TestCase):
    def test_rejects_fixture_failure_even_if_driver_claims_success(self):
        for lines in (
            'FIXTURES_ERROR Internal snapshot marker changed: blocks\nREVIEW_PASS CORRECTNESS_PASSED',
            'REVIEW_PASS CORRECTNESS_PASSED\nFIXTURES_ERROR Internal snapshot marker changed: blocks',
        ):
            with self.subTest(lines=lines), self.assertRaises(RuntimeError):
                REVIEW.validate_log(lines, 'CORRECTNESS_PASSED')

    def test_rejects_review_failure_and_missing_completion(self):
        for lines in ('REVIEW_CORRECTNESS_FAILED step=5', 'REVIEW_PASS C11'):
            with self.subTest(lines=lines), self.assertRaises(RuntimeError):
                REVIEW.validate_log(lines, 'CORRECTNESS_PASSED')

    def test_accepts_clean_completion(self):
        REVIEW.validate_log('FIXTURES_CHECK_OK\nREVIEW_PASS CORRECTNESS_PASSED', 'CORRECTNESS_PASSED')

    def test_rejects_client_success_if_movement_disconnected_a_client(self):
        with self.assertRaises(RuntimeError):
            REVIEW.validate_log('Invalid move player packet received\nREVIEW_PASS CLIENT_ACCEPTANCE_PASSED',
                                'CLIENT_ACCEPTANCE_PASSED')


if __name__ == '__main__':
    unittest.main()
