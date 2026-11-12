
"""Validate the live catalog and source mappings without needing an Android device."""
import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def validate(catalog):
    if catalog.get("schemaVersion") != 1:
        raise ValueError("unsupported schema")
    seen = set()
    for app in catalog["apps"]:
        if not app.get("id") or app["id"] in seen:
            raise ValueError("duplicate/missing id")
        seen.add(app["id"])
        if app["status"] == "active":
            if not app.get("packageName"):
                raise ValueError("missing package")
            source = app["source"]
            if not source.get("url", "").startswith("https://"):
                raise ValueError("HTTPS required")
            if source["type"] not in ("apk", "json"):
                raise ValueError("unknown source")
        elif app["status"] != "pending":
            raise ValueError("unknown status")

class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "catalog.json").read_text())
    def test_bundled_catalog_matches_remote_candidate(self):
        bundled = json.loads((ROOT / "app/src/main/assets/catalog.json").read_text())
        self.assertEqual(self.catalog, bundled)
    def test_direct_download_metadata(self):
        direct = [a for a in self.catalog["apps"] if a["source"]["type"] == "apk"]
        self.assertEqual({a["id"] for a in direct}, {"shield-bridge", "car-launcher", "download-center"})
        for a in direct:
            self.assertEqual(a["status"], "active")
            self.assertRegex(a["sha256"], r"^[a-f0-9]{64}$")
            self.assertGreater(a["sizeBytes"], 0)
            self.assertGreater(a["versionCode"], 0)
    def test_catalog_contract(self):
        validate(self.catalog)
    def test_carsync_mapping_matches_existing_release(self):
        entry = next(a for a in self.catalog["apps"] if a["id"] == "carsync")
        release = json.loads((ROOT.parent / "standalone-update.json").read_text())
        fields = entry["source"]["fields"]
        self.assertEqual(entry["packageName"], release[fields["packageName"]])
        self.assertTrue(release[fields["apkUrl"]].startswith("https://"))
        self.assertRegex(release[fields["sha256"]], r"^[a-f0-9]{64}$")
    def test_duplicate_id_rejected(self):
        self.catalog["apps"].append(self.catalog["apps"][0])
        with self.assertRaises(ValueError): validate(self.catalog)
    def test_http_source_rejected(self):
        entry = next(a for a in self.catalog["apps"] if a["status"] == "active")
        entry["source"]["url"] = "http://example.com/update.json"
        with self.assertRaises(ValueError): validate(self.catalog)
    def test_pending_entries_do_not_invent_downloads(self):
        for app in self.catalog["apps"]:
            if app["status"] == "pending":
                self.assertFalse(app["source"].get("url"))

if __name__ == "__main__":
    unittest.main()
