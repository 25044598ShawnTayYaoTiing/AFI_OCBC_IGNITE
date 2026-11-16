"""Generate distinct fictional PDF inputs for the 100 seeded scenario profiles.
Scenario labels describe submitted evidence. They never assign customer risk attributes.
"""
import argparse
import json
from pathlib import Path
from service_request_e2e import pdf


def generate(output: Path):
    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for number in range(1, 101):
        reference = f"SYN-DEMO-{number:03d}"
        scenario = "CLEAN" if number <= 50 else "MISSING_SUPPORT" if number <= 75 else "ADDRESS_CONFLICT" if number <= 90 else "IDENTITY_CONFLICT"
        address = f"{1000 + number} Fictional Service Road"
        identity = "DEMO-CONFLICT-ONLY" if scenario == "IDENTITY_CONFLICT" else "DEMO-ID-" + reference
        request = f"{number:03d}-request.pdf"
        (output / request).write_bytes(pdf(["Banking Service Request", "SYNTHETIC - NOT AN OFFICIAL BANK FORM", f"Customer Name: Fictional Customer {number}", "Customer Reference: " + reference, "Identity Number: " + identity, "Request Type: ADDRESS_CHANGE", "Mailing Address: " + address, "Signature: Present", "Declaration Date: 2026-11-14"]))
        files = [request]
        if scenario != "MISSING_SUPPORT":
            support = f"{number:03d}-support.pdf"
            supplied_address = f"{number} Conflicting Fictional Road" if scenario == "ADDRESS_CONFLICT" else address
            (output / support).write_bytes(pdf(["Supporting Document", "SYNTHETIC - NOT AN OFFICIAL BANK FORM", "Customer Reference: " + reference, "Mailing Address: " + supplied_address]))
            files.append(support)
        manifest.append({"fixture": number, "customerUsername": f"synthetic-{number:03d}", "scenario": scenario, "expectedRisk": {"CLEAN": 0, "MISSING_SUPPORT": 15, "ADDRESS_CONFLICT": 35, "IDENTITY_CONFLICT": 80}[scenario], "files": files, "provenance": "SYNTHETIC_CASE_EVIDENCE_NOT_CUSTOMER_RISK"})
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Generated 100 synthetic scenarios / 50 clean, 25 low, 15 medium, 10 high")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path(".local/mentor-fixtures"))
    generate(parser.parse_args().output_dir)
