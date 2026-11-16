"""Ephemeral loopback-only SMTP sink for synthetic E2E checks. Never forwards email.

The test harness can read received synthetic OTPs through its local mailbox API.
Do not deploy this test fixture or use it for real customer messages.
"""
import asyncio
import json
from email import policy
from email.parser import BytesParser

messages = []


async def smtp(reader, writer):
    async def reply(value):
        writer.write((value + '\r\n').encode()); await writer.drain()
    await reply('220 AFI synthetic local sink')
    recipients = []
    try:
        while line := await asyncio.wait_for(reader.readline(), 30):
            command = line.decode(errors='replace').strip()
            upper = command.upper()
            if upper.startswith(('EHLO', 'HELO')):
                await reply('250 localhost')
            elif upper.startswith('MAIL FROM:'):
                recipients = []; await reply('250 OK')
            elif upper.startswith('RCPT TO:'):
                if not command.rstrip('>').lower().endswith(('.test', '.invalid')):
                    await reply('550 Synthetic reserved destinations only')
                else:
                    recipients.append(command[8:].strip()); await reply('250 OK')
            elif upper == 'DATA' and recipients:
                await reply('354 End with dot'); data = bytearray()
                while chunk := await asyncio.wait_for(reader.readline(), 30):
                    if chunk in (b'.\r\n', b'.\n'): break
                    data.extend(chunk)
                    if len(data) > 100_000: raise ValueError('Synthetic message too large')
                parsed = BytesParser(policy=policy.default).parsebytes(bytes(data))
                body = parsed.get_body(preferencelist=('plain',))
                messages.append({'to': recipients, 'subject': str(parsed['Subject'] or ''),
                                 'body': body.get_content() if body else ''})
                del messages[:-100]
                await reply('250 Accepted into the local synthetic sink')
            elif upper == 'QUIT':
                await reply('221 Bye'); break
            elif upper == 'RSET':
                recipients = []; await reply('250 OK')
            else:
                await reply('250 OK')
    finally:
        writer.close(); await writer.wait_closed()


async def http(reader, writer):
    try:
        header = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 5)
        path = header.split(b' ', 2)[1]
        data = messages if path == b'/messages' else {'status': 'UP'}
        body = json.dumps(data).encode()
        writer.write(f'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\n\r\n'.encode() + body)
        await writer.drain()
    finally:
        writer.close(); await writer.wait_closed()


async def main():
    server = await asyncio.start_server(smtp, '127.0.0.1', 11025)
    api = await asyncio.start_server(http, '127.0.0.1', 18025)
    async with server, api:
        await asyncio.gather(server.serve_forever(), api.serve_forever())


if __name__ == '__main__':
    asyncio.run(main())
