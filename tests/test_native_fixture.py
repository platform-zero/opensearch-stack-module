#!/usr/bin/env python3
from pathlib import Path
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'stack.kotlin/test-runner/src/main/kotlin/org/webservices/testrunner/suites/SearchServiceTests.kt'

class NativeFixtureTests(unittest.TestCase):
    def test_isolated_new_native_index_not_owner_knowledge(self):
        text = SOURCE.read_text()
        self.assertIn('UUID.randomUUID()', text)
        self.assertIn('p0-acceptance-$identity', text)
        self.assertIn('p0_acceptance_identity', text)
        self.assertNotIn('/knowledge', text)
        self.assertIn('client.putRaw', text)
        self.assertIn('client.deleteRaw("$base/$index")', text)
        self.assertIn('} finally {', text)

    def test_browser_read_accepts_empty_fresh_namespace_without_mutation(self):
        path = Path(__file__).resolve().parents[1] / 'stack.containers/test-runner/playwright-tests/tests/deep/forward-auth/search.spec.ts'
        text = path.read_text()
        self.assertIn('/knowledge/_search?ignore_unavailable=true&allow_no_indices=true', text)
        self.assertIn("expect(search.status", text)
        self.assertIn('Array.isArray', text)
        self.assertNotIn("method: 'PUT'", text)
        self.assertNotIn("method: 'DELETE'", text)

    def test_health_credentials_are_private_stdin_not_argv(self):
        text = (SOURCE.parents[9] / 'stack.runtime.yaml').read_text()
        self.assertIn('--config -', text)
        self.assertNotIn('--user', text)
        self.assertNotIn('--insecure', text)

    def test_native_identity_and_negative_status_required(self):
        text = SOURCE.read_text()
        self.assertIn('indexName = index', text)
        self.assertIn('require(!result.success)', text)
        self.assertIn('== document', text)
        self.assertIn('contains(term)', text)

if __name__ == '__main__':
    unittest.main()
