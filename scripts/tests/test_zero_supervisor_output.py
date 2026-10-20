"""Bounded production capture, plus real POSIX process/pipe integration."""
import ast
import os
import selectors
import subprocess
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[2] / 'services/src/main/assets/zero_sdk/zero_supervisor.py'


def production():
    names = {'OutputCapture', 'collect_exec', 'exec_cmd', '_exec_cmd', '_shq', 'tail_bytes'}
    nodes = [n for n in ast.parse(SOURCE.read_text(encoding='utf-8')).body if getattr(n, 'name', None) in names]
    env = dict(os=os, selectors=selectors, subprocess=subprocess, time=time, Path=Path, uuid=uuid,
               TRUNCATE_BYTES=65536, OUTPUT_FILE_LIMIT=2*1024*1024, OUTPUT_KEEP=32,
               LOCK=threading.RLock(), EXEC_SLOTS=threading.BoundedSemaphore(4), ACTIVE_OUTPUTS=set(),
               SESSIONS={}, EXEC_TIMEOUT_DEFAULT=120, DEB_CMD=[], deb_path=lambda p:p)
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), 'exec'), env)
    return env


class OutputTest(unittest.TestCase):
    def test_large_dual_output_is_bounded_and_original_prefix_readable(self):
        env = production()
        with tempfile.TemporaryDirectory() as root:
            capture = env['OutputCapture'](Path(root)/'out')
            with capture:
                for i in range(1024):
                    capture.append(bytes([i % 251])*16384)
            self.assertEqual(16*1024*1024, capture.total)
            self.assertEqual(65536, len(capture.tail))
            self.assertEqual(2*1024*1024, capture.path.stat().st_size)
            self.assertEqual(bytes([1023 % 251])*16384, capture.tail[-16384:])
            self.assertEqual(bytes(16384), capture.path.read_bytes()[:16384])

    @unittest.skipUnless(os.name == 'posix', 'real pipes require POSIX; also run on Termux')
    def test_real_dual_stream_exit_timeout_and_recovery(self):
        env = production()
        import sys
        sys.path.insert(0, str(SOURCE.parent))
        from zero_release import process_record, process_snapshot, process_tree, kill_process_tree
        env.update(process_record=process_record, process_snapshot=process_snapshot,
                   process_tree=process_tree, kill_process_tree=kill_process_tree)
        with tempfile.TemporaryDirectory() as root:
            env['OUTPUT_DIR'] = root
            command = "python3 -c 'import sys; sys.stdout.write(\"x\"*5000000); sys.stderr.write(\"y\"*5000000); sys.exit(7)'"
            result = env['exec_cmd'](dict(cmd=command, cwd=root, timeout_s=10))
            self.assertEqual(7, result['exit'])
            self.assertEqual(65536, len(result['stdout']))
            self.assertTrue(result['output_files']['stdout']['truncated'])
            self.assertTrue(result['stderr'].endswith('y'))
            result = env['exec_cmd'](dict(cmd='sleep 30', cwd=root, timeout_s=.2))
            self.assertTrue(result['timed_out'])
            self.assertTrue(result['cleanup_ok'])
            self.assertEqual('alive', env['exec_cmd'](dict(cmd='printf alive', cwd=root))['stdout'])


if __name__ == '__main__':
    unittest.main()
