import unittest
import xml.etree.ElementTree as ET

from check_release_apk import ANDROID, DOMAINS, check_policy


class ReleasePolicyTest(unittest.TestCase):
    def fixture(self):
        manifest = ET.Element("manifest")
        ET.SubElement(manifest, "application", {ANDROID + "allowBackup": "false", ANDROID + "fullBackupContent": "false", ANDROID + "dataExtractionRules": "@xml/release_data_extraction_rules"})
        rules = ET.Element("data-extraction-rules")
        for section in ["cloud-backup", "device-transfer"]:
            node = ET.SubElement(rules, section)
            for domain in DOMAINS:
                ET.SubElement(node, "exclude", {"domain": domain, "path": "."})
        return manifest, rules

    def test_release_exclusions_pass_but_allowing_debug_or_backup_fails(self):
        manifest, rules = self.fixture()
        check_policy(manifest, rules)
        for flag in ["debuggable", "allowBackup", "fullBackupContent"]:
            with self.subTest(flag=flag):
                manifest, rules = self.fixture()
                manifest.find("application").set(ANDROID + flag, "true")
                with self.assertRaises(ValueError):
                    check_policy(manifest, rules)

    def test_missing_device_transfer_or_one_unprotected_domain_is_rejected(self):
        for missing_section in [True, False]:
            manifest, rules = self.fixture()
            node = rules.find("device-transfer")
            if missing_section:
                rules.remove(node)
            else:
                node.remove(node[0])
            with self.assertRaises(ValueError):
                check_policy(manifest, rules)

    def test_debug_test_activity_is_rejected_even_when_debuggable_is_false(self):
        manifest, rules = self.fixture()
        ET.SubElement(manifest.find("application"), "activity", {ANDROID + "name": "com.zero.v2.app.acceptance.SyntheticActivity"})
        with self.assertRaises(ValueError):
            check_policy(manifest, rules)


if __name__ == "__main__":
    unittest.main()
