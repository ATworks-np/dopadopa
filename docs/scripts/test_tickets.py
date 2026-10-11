"""Small regression tests for docs/scripts/tickets.py, no extra dependencies."""
import unittest
from pathlib import Path
import importlib.util

spec=importlib.util.spec_from_file_location('ticket_cli',Path(__file__).with_name('tickets.py'))
tracker=importlib.util.module_from_spec(spec)
spec.loader.exec_module(tracker)

class TrackerTests(unittest.TestCase):
    def test_all_ticket_ids_unique_and_schema_valid(self):
        items=tracker.all_tickets()
        self.assertGreaterEqual(len(items),16)
        self.assertEqual([],tracker.validate(items))
    def test_all_dependencies_known_and_acyclic(self):
        items=tracker.all_tickets()
        for v in items.values():
            for x in v['depends_on']:
                self.assertIn(x,items)
    def test_checkbox_guard(self):
        self.assertTrue(tracker.checkboxes('## 受け入れ条件\n- [ ] A\n## 検証記録\n- PASS'))
        self.assertEqual([],tracker.checkboxes('## 受け入れ条件\n- [x] A\n## 検証記録\n- PASS'))
    def test_board_generation(self):
        board=tracker.board_text(tracker.all_tickets())
        self.assertIn('DG-001',board)
        self.assertIn('DG-016',board)

if __name__=='__main__':unittest.main()
