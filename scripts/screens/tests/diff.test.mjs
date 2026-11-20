import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { compareImages, decodePng, encodePng } from '../diff.mjs';

const cli = fileURLToPath(new URL('../diff.mjs', import.meta.url));
const before = { width: 2, height: 1, pixels: Buffer.from([10, 20, 30, 255, 100, 110, 120, 255]) };
const after = { width: 2, height: 1, pixels: Buffer.from([10, 20, 30, 255, 101, 110, 120, 255]) };
const run = (...args) => spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' });

async function fixture(fn) {
    const dir = await mkdtemp(join(tmpdir(), 'fastbite-screenshot-diff-'));
    try {
        const a = join(dir, 'before'), b = join(dir, 'after'), output = join(dir, 'diff');
        await mkdir(join(a, 'cafe'), { recursive: true });
        await mkdir(join(b, 'cafe'), { recursive: true });
        await writeFile(join(a, 'cafe', 'page.png'), encodePng(before));
        await writeFile(join(b, 'cafe', 'page.png'), encodePng(after));
        await fn({ dir, a, b, output });
    } finally {
        await rm(dir, { recursive: true, force: true });
    }
}

test('RGBA PNGs round-trip and exact comparisons count changed pixels', () => {
    assert.deepEqual(decodePng(encodePng(before)), before);
    assert.equal(compareImages(before, before).changed, 0);
    const result = compareImages(before, after);
    assert.equal(result.changed, 1);
    assert.deepEqual(result.diff.pixels, Buffer.from([0, 0, 0, 0, 255, 0, 0, 255]));
    assert.throws(() => compareImages(before, { ...after, width: 1 }), /sizes differ/);
});

test('invalid and corrupted PNGs are refused', () => {
    assert.throws(() => decodePng(Buffer.from('not PNG')), /Not a PNG/);
    const corrupt = encodePng(before);
    corrupt[20] ^= 1;
    assert.throws(() => decodePng(corrupt), /checksum/);
});

test('CLI compares matching trees and writes a PNG for differences only', async () => {
    await fixture(async ({ a, b, output }) => {
        let result = run(a, b, output);
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /cafe\/page\.png: 1 changed pixels/);
        assert.deepEqual(decodePng(await readFile(join(output, 'cafe', 'page.png'))).pixels,
            Buffer.from([0, 0, 0, 0, 255, 0, 0, 255]));
        result = run(a, b, output);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /EEXIST/);
    });
});

test('CLI reports identical trees without creating diff images', async () => {
    await fixture(async ({ a, b, output }) => {
        await writeFile(join(b, 'cafe', 'page.png'), encodePng(before));
        const result = run(a, b, output);
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /Total: 0 changed pixels across 1 images/);
        await assert.rejects(readFile(join(output, 'cafe', 'page.png')), { code: 'ENOENT' });
    });
});

test('CLI writes changed screenshots to a separate default output tree', async () => {
    await fixture(async ({ a, b }) => {
        const result = run(a, b);
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(decodePng(await readFile(join(`${b}-diff`, 'cafe', 'page.png'))).pixels,
            Buffer.from([0, 0, 0, 0, 255, 0, 0, 255]));
    });
});

test('CLI refuses missing, empty and unmatched screenshot trees', async () => {
    await fixture(async ({ dir, a, b, output }) => {
        let result = run(join(dir, 'missing'), b, output);
        assert.notEqual(result.status, 0);
        await rm(join(a, 'cafe', 'page.png'));
        result = run(a, b, output);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /must contain PNG/);
        await writeFile(join(a, 'cafe', 'other.png'), encodePng(before));
        result = run(a, b, output);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /Missing (baseline|after)/);
    });
});

test('CLI rejects output paths that alias an input through a symlink', async t => {
    await fixture(async ({ dir, a, b }) => {
        const alias = join(dir, 'alias');
        try { await symlink(a, alias, process.platform === 'win32' ? 'junction' : 'dir'); }
        catch (error) {
            if (error.code === 'EPERM' || error.code === 'EACCES') { t.skip('Symlink creation not permitted'); return; }
            throw error;
        }
        const result = run(a, b, join(alias, 'diff'));
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /Symbolic links/);
    });
});

test('CLI rejects missing screenshots, mismatched sizes and output inside inputs', async () => {
    await fixture(async ({ a, b, output }) => {
        await writeFile(join(b, 'extra.png'), encodePng(before));
        let result = run(a, b, output);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /Missing baseline/);
        await rm(join(b, 'extra.png'));
        await writeFile(join(b, 'cafe', 'page.png'), encodePng({ width: 1, height: 1, pixels: Buffer.alloc(4) }));
        result = run(a, b, output);
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /sizes differ/);
        result = run(a, b, join(a, 'output'));
        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /separate/);
    });
});
