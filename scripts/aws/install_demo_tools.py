"""One-time EC2 install of deployment helpers; no service, secret or data changes."""
import argparse
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import urlopen

FILES = ['scripts/aws/log_redaction.py', 'scripts/aws/check_public_endpoints.py', 'scripts/aws/deploy.py']


def install(revision, root=Path('/opt/afi-resolve')):
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('Use the full reviewed main commit SHA.')
    root = root.resolve()
    if root != Path('/opt/afi-resolve') or not (root / '.deploy.json').is_file():
        raise ValueError('Expected the existing /opt/afi-resolve deployment; no files changed.')
    config = json.loads((root / '.deploy.json').read_text())
    if not config.get('env_files'):
        raise ValueError('Existing private environment configuration is required.')
    # Fetch and syntax-check all files before modifying any installed helper.
    contents = {}
    for relative in FILES:
        url = 'https://raw.githubusercontent.com/25044598ShawnTayYaoTiing/AFI_OCBC_IGNITE/' + revision + '/' + relative
        with urlopen(url, timeout=30) as response:
            contents[relative] = response.read().decode('utf-8')
        compile(contents[relative], relative, 'exec')
    backup = root / '.local/tool-backups' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    for relative, content in contents.items():
        target = root / relative
        if target.is_file():
            saved = backup / relative
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, saved)
        temporary = target.with_suffix('.installing')
        temporary.write_text(content, encoding='utf-8')
        temporary.replace(target)
    print('Installed demo deployment helpers from ' + revision)
    print('Prior helpers backed up under ' + str(backup))
    print('Services, .env, .deploy.json, Compose configuration, RDS and S3 were unchanged.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', required=True)
    install(parser.parse_args().revision)
