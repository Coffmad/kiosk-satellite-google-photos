"""Check asset collection before the build writes a package."""
import os
import tempfile
import unittest
from pathlib import Path
from plugin_assets import asset_files

class AssetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'assets/dvd').mkdir(parents=True)

    def test_assets_above_inline_limit_keep_their_paths(self):
        target = self.root / 'assets/dvd/photo.png'
        target.write_bytes(b'x' * 600000)
        self.assertEqual(asset_files(self.root), [('assets/dvd/photo.png', target)])

    @unittest.skipIf(os.name == 'nt', 'Windows needs a privilege to create symbolic links')
    def test_symbolic_links_are_rejected(self):
        (self.root / 'assets/link').symlink_to(self.root / 'assets/dvd', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'symbolic'):
            asset_files(self.root)

    @unittest.skipIf(os.name == 'nt', 'Windows cannot create files named with ? or #')
    def test_ambiguous_url_characters_are_rejected(self):
        for name in ('bad%20name.png', 'file?.png', 'file#.png', '.hidden'):
            target = self.root / 'assets' / name
            target.write_text('x')
            with self.assertRaisesRegex(ValueError, 'Invalid asset path'):
                asset_files(self.root)
            target.unlink()

if __name__ == '__main__':
    unittest.main()
