"""Shared secret redaction for application and AWS deployment diagnostics."""
import os
import re

SENSITIVE = re.compile(r'password|secret|token|credential|api.?key|access.?key|authorization|smtp.username|mail.from', re.I)


class Redactor:
    def __init__(self, values=None):
        self.values = set()
        self.add(os.environ if values is None else values)

    def add(self, values):
        for key, value in values.items():
            if SENSITIVE.search(key) and value and len(str(value)) >= 4:
                self.values.add(str(value))

    def __call__(self, text):
        result = str(text)
        for value in sorted(self.values, key=len, reverse=True):
            result = result.replace(value, '[REDACTED]')
        result = re.sub(r'-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----', '[REDACTED PRIVATE KEY]', result)
        result = re.sub(r'(?i)(authorization\s*[:=]\s*)(?:basic|bearer)\s+[^\s,;]+', r'\1[REDACTED]', result)
        result = re.sub(r'(?i)((?:password|passwd|secret|token|api[_-]?key|access[_-]?key|smtp_username)\s*[=:]\s*)[^\s,;\"\']+', r'\1[REDACTED]', result)
        result = re.sub(r'(://)[^/@\s]+:[^/@\s]+@', r'\1[REDACTED]@', result)
        result = re.sub(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b', '[REDACTED AWS KEY]', result)
        result = re.sub(r'\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b', '[REDACTED JWT]', result)
        result = re.sub(r'[A-Za-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', '[REDACTED EMAIL]', result)
        return result
