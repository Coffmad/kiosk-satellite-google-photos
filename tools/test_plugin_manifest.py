"""Check release overrides and preservation of the development manifest."""
import json
from pathlib import Path
import tempfile
import unittest

from plugin_manifest import build_manifest


class BuildManifestTest(unittest.TestCase):
    def test_release_version_overrides_source_without_editing_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            original = b'{"id":"demo","version":"1.0.1","apiVersion":1,"description":"Demo","settings":[]}'
            path.write_bytes(original)
            self.assertEqual(build_manifest(path), original)
            release = json.loads(build_manifest(path, '1.2.0'))
            self.assertEqual(release, {**json.loads(original), 'version': '1.2.0'})
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(json.loads(build_manifest(path, '1.3.0-beta.1'))['version'], '1.3.0-beta.1')

    def test_invalid_versions_cannot_become_package_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'kiosk-satellite-plugin.json'
            path.write_text('{"version":"1.0.1"}')
            for version in ['', 'v1.2.0', '1.2', '../1.2.0', '1.2.0/other', '1.2.0\n', '1.2.0-' + 'a' * 40]:
                with self.subTest(version=version), self.assertRaises(ValueError):
                    build_manifest(path, version)


if __name__ == '__main__':
    unittest.main()
