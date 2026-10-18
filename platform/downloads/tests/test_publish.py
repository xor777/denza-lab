import unittest

from downloads.publish import PublishError, file_name, parse_badging

BADGING = (
    "package: name='ru.yandex.yandexnavi' versionCode='739494301' versionName='29.8.1-YandexHUD.1' "
    "platformBuildVersionName='15'\nsdkVersion:'26'\n"
)


class PublishTest(unittest.TestCase):
    def test_reads_package_and_versions_from_badging(self):
        self.assertEqual(parse_badging(BADGING), ("ru.yandex.yandexnavi", 739494301, "29.8.1-YandexHUD.1"))

    def test_badging_without_a_package_line_fails(self):
        with self.assertRaises(PublishError):
            parse_badging("sdkVersion:'26'\n")

    def test_file_name_carries_the_app_and_version(self):
        self.assertEqual(file_name("yandex-hud", "29.8.1-YandexHUD.1"), "yandex-hud-29.8.1-YandexHUD.1.apk")
        with self.assertRaises(PublishError):
            file_name("hud-speed", "79.0 beta/1")


if __name__ == "__main__":
    unittest.main()
