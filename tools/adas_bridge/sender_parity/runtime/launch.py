#!/usr/bin/env python3
"""Run an unchanged private runner using an explicitly pinned Frida 17 prelude.

This launcher performs no device operation itself. The forwarded runner retains
its own emulator checks, lock, bounded execution, capture, and cleanup.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import runpy
import sys


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(runner, forwarded, config_path):
    import frida

    runner, config_path = Path(runner).resolve(), Path(config_path).resolve()
    config = json.loads(config_path.read_text())
    if frida.__version__ != config['frida_version']:
        raise ValueError('Frida client differs from the explicit runtime configuration')
    prefix_path = Path(config['prelude_path'])
    if not prefix_path.is_absolute():
        prefix_path = config_path.parent / prefix_path
    prefix_bytes = prefix_path.read_bytes()
    if digest(prefix_bytes) != config['prelude_sha256']:
        raise ValueError('Frida prelude differs from its pinned hash')
    prefix = prefix_bytes.decode('utf-8')
    if prefix.startswith('📦'):
        raise ValueError('Prelude must be a concatenable IIFE, not an ESM bundle')
    output_args = argparse.ArgumentParser(add_help=False)
    output_args.add_argument('--out', required=True, type=Path)
    out = output_args.parse_known_args(forwarded)[0].out.resolve()
    original_create = frida.core.Session.create_script
    original_argv = sys.argv[:]
    metadata = None

    def create_script(session, source, *args, **kwargs):
        nonlocal metadata
        if metadata is not None:
            raise ValueError('This capture launcher expects exactly one agent')
        if not isinstance(source, str):
            raise TypeError('Expected the private runner to pass a text agent')
        source_bytes = source.encode('utf-8')
        actual = prefix + '\n;/* original private runner agent follows */\n' + source
        actual_bytes = actual.encode('utf-8')
        out.mkdir(parents=True, exist_ok=True)
        (out / 'agent.original.js').write_bytes(source_bytes)
        (out / 'agent.js').write_bytes(actual_bytes)
        metadata = {
            'schema': 'denza-sender-parity-runtime17-v1',
            'frida_client_version': frida.__version__,
            'bridge_version': config['bridge_version'],
            'bridge_git_head': config['bridge_git_head'],
            'server_sha256': config['server_sha256'],
            'server_identity': 'configured pin; device server identity is verified by the owner',
            'runner_path': str(runner),
            'runner_sha256': digest(runner.read_bytes()),
            'config_path': str(config_path),
            'config_sha256': digest(config_path.read_bytes()),
            'prelude_path': str(prefix_path.resolve()),
            'prelude_sha256': digest(prefix_bytes),
            'original_agent_sha256': digest(source_bytes),
            'actual_agent_sha256': digest(actual_bytes),
            'actual_agent_path': str(out / 'agent.js'),
            'script_create_succeeded': False,
        }
        try:
            script = original_create(session, actual, *args, **kwargs)
            metadata['script_create_succeeded'] = True
            return script
        finally:
            (out / 'runtime17.json').write_text(json.dumps(metadata, indent=2) + '\n')

    frida.core.Session.create_script = create_script
    try:
        sys.argv = [str(runner)] + list(forwarded)
        runpy.run_path(str(runner), run_name='__main__')
    finally:
        sys.argv = original_argv
        frida.core.Session.create_script = original_create
        if metadata is not None:
            report_path = out / 'report.json'
            if report_path.exists():
                report = json.loads(report_path.read_text())
                report['original_agent_sha256'] = metadata['original_agent_sha256']
                report['agent_sha256'] = metadata['actual_agent_sha256']
                report['runtime17'] = metadata
                report_path.write_text(json.dumps(report, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runner', required=True, type=Path)
    parser.add_argument('--runtime-config', type=Path,
                        default=Path(sys.prefix).parent / 'runtime-config.json')
    parser.add_argument('runner_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    forwarded = args.runner_args
    if forwarded[:1] == ['--']:
        forwarded = forwarded[1:]
    run(args.runner, forwarded, args.runtime_config)


if __name__ == '__main__':
    main()
