"""Regression coverage for platform names found on GitHub Actions runners."""
from pathlib import Path
import tempfile
import unittest

from android_sdk import android_platform


class AndroidPlatformTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.sdk = Path(self.directory.name)

    def install(self, version):
        jar = self.sdk / 'platforms' / f'android-{version}' / 'android.jar'
        jar.parent.mkdir(parents=True)
        jar.touch()
        return jar

    def test_integer_and_dotted_versions_sort_numerically(self):
        for version in ['9', '35', '37.0', '37.2']:
            self.install(version)
        expected = self.install('37.10')
        self.assertEqual(android_platform(self.sdk), expected)

    def test_preview_and_extension_directories_do_not_break_discovery(self):
        expected = self.install('35')
        for version in ['Baklava', '37.0-preview', '35-ext15']:
            self.install(version)
        self.assertEqual(android_platform(self.sdk), expected)

    def test_release_platform_is_selected_even_with_newer_preinstalls(self):
        expected = self.install('35')
        self.install('37.0')
        self.assertEqual(android_platform(self.sdk, '35'), expected)
        self.assertEqual(android_platform(self.sdk, '37.0'), android_platform(self.sdk))

    def test_missing_release_platform_never_falls_back(self):
        self.install('37.0')
        with self.assertRaisesRegex(ValueError, 'Install Android platform android-35'):
            android_platform(self.sdk, '35')

    def test_missing_platforms_and_invalid_requests_have_clear_errors(self):
        with self.assertRaisesRegex(ValueError, 'Install a numbered Android SDK platform'):
            android_platform(self.sdk)
        with self.assertRaisesRegex(ValueError, 'numeric version'):
            android_platform(self.sdk, '../35')


if __name__ == '__main__':
    unittest.main()
