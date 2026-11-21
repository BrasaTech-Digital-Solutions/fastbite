#!/usr/bin/env node
// Zero-dependency PNG pixel diff for FastBite screenshots.
// Usage: node scripts/screens/diff.mjs <dirA> <dirB> [outputDir]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const PNG_SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

const CRC_TABLE = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  CRC_TABLE[n] = c >>> 0;
}

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  if (pb <= pc) return b;
  return c;
}

export function decodePng(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 8 || !buf.subarray(0, 8).equals(PNG_SIG)) {
    throw new Error('Not a PNG file');
  }
  let offset = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0;
  const idatChunks = [];
  while (offset + 12 <= buf.length) {
    const len = buf.readUInt32BE(offset);
    if (offset + 12 + len > buf.length) throw new Error('Truncated PNG chunk');
    const typeBuf = buf.subarray(offset + 4, offset + 8);
    const type = typeBuf.toString('ascii');
    const data = buf.subarray(offset + 8, offset + 8 + len);
    const expectedCrc = buf.readUInt32BE(offset + 8 + len);
    const actualCrc = crc32(buf.subarray(offset + 4, offset + 8 + len));
    if (actualCrc !== expectedCrc) {
      throw new Error(`Invalid PNG chunk checksum in ${type}`);
    }
    offset += 12 + len;
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idatChunks.push(data);
    } else if (type === 'IEND') {
      break;
    }
  }
  if (bitDepth !== 8 || (colorType !== 2 && colorType !== 6)) {
    throw new Error(`Unsupported PNG format: bitDepth=${bitDepth} colorType=${colorType}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idatChunks));
  const stride = width * bpp;
  const recon = Buffer.alloc(height * stride);
  let inPos = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[inPos++];
    const rowStart = y * stride;
    for (let x = 0; x < stride; x++) {
      const val = raw[inPos++];
      const a = x >= bpp ? recon[rowStart + x - bpp] : 0;
      const b = y > 0 ? recon[rowStart - stride + x] : 0;
      const c = (x >= bpp && y > 0) ? recon[rowStart - stride + x - bpp] : 0;
      let out;
      if (filter === 0) out = val;
      else if (filter === 1) out = (val + a) & 0xff;
      else if (filter === 2) out = (val + b) & 0xff;
      else if (filter === 3) out = (val + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) out = (val + paeth(a, b, c)) & 0xff;
      else throw new Error(`Unknown PNG filter ${filter}`);
      recon[rowStart + x] = out;
    }
  }
  const pixels = Buffer.alloc(width * height * 4);
  if (bpp === 4) {
    recon.copy(pixels);
  } else {
    for (let i = 0, j = 0; i < recon.length; i += 3, j += 4) {
      pixels[j] = recon[i];
      pixels[j + 1] = recon[i + 1];
      pixels[j + 2] = recon[i + 2];
      pixels[j + 3] = 255;
    }
  }
  return { width, height, pixels };
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

export function encodePng({ width, height, pixels }) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const idat = zlib.deflateSync(raw);
  return Buffer.concat([PNG_SIG, pngChunk('IHDR', ihdr), pngChunk('IDAT', idat), pngChunk('IEND', Buffer.alloc(0))]);
}

export function compareImages(a, b) {
  if (a.width !== b.width || a.height !== b.height) {
    throw new Error(`Image sizes differ: ${a.width}x${a.height} vs ${b.width}x${b.height}`);
  }
  const count = a.width * a.height;
  const diffPixels = Buffer.alloc(count * 4);
  let changed = 0;
  for (let i = 0; i < count; i++) {
    const idx = i * 4;
    if (
      a.pixels[idx] !== b.pixels[idx] ||
      a.pixels[idx + 1] !== b.pixels[idx + 1] ||
      a.pixels[idx + 2] !== b.pixels[idx + 2] ||
      a.pixels[idx + 3] !== b.pixels[idx + 3]
    ) {
      changed++;
      diffPixels[idx] = 255;
      diffPixels[idx + 1] = 0;
      diffPixels[idx + 2] = 0;
      diffPixels[idx + 3] = 255;
    }
  }
  return { changed, diff: { width: a.width, height: a.height, pixels: diffPixels } };
}

function walkPngs(root, rel = '') {
  const results = [];
  const full = path.join(root, rel);
  for (const entry of fs.readdirSync(full, { withFileTypes: true })) {
    const childRel = rel ? `${rel}/${entry.name}` : entry.name;
    if (entry.isSymbolicLink()) throw new Error(`Symbolic links are not supported in screenshot trees: ${childRel}`);
    if (entry.isDirectory()) results.push(...walkPngs(root, childRel));
    else if (entry.isFile() && entry.name.toLowerCase().endsWith('.png')) results.push(childRel);
  }
  return results.sort();
}

function isInside(child, parent) {
  const rel = path.relative(path.resolve(parent), path.resolve(child));
  return rel === '' || (rel !== '..' && !rel.startsWith(`..${path.sep}`) && !path.isAbsolute(rel));
}

// Reject symlinked components before any read or write. Input paths can otherwise
// alias the output, and pre-existing output subdirectories could escape its root.
function rejectSymlinks(value) {
  let current = path.resolve(value);
  while (true) {
    try {
      if (fs.lstatSync(current).isSymbolicLink()) throw new Error(`Symbolic links are not supported: ${current}`);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
}

function runCli(argv) {
  const args = argv.slice(2);
  let dirA = null, dirB = null, outDir = null;
  for (const a of args) {
    if (a.startsWith('--diff-dir=')) outDir = a.slice(11);
    else if (!dirA) dirA = a;
    else if (!dirB) dirB = a;
    else if (!outDir) outDir = a;
  }
  if (!dirA || !dirB) {
    console.error('Usage: node scripts/screens/diff.mjs <dirA> <dirB> [outputDir]');
    process.exit(2);
  }
  dirA = path.resolve(dirA);
  dirB = path.resolve(dirB);
  outDir = path.resolve(outDir || `${dirB}-diff`);
  rejectSymlinks(dirA);
  rejectSymlinks(dirB);
  rejectSymlinks(outDir);
  if (isInside(outDir, dirA) || isInside(outDir, dirB) || isInside(dirA, outDir) || isInside(dirB, outDir)) {
    throw new Error('Output directory must be separate from input directories.');
  }
  if (!fs.statSync(dirA).isDirectory() || !fs.statSync(dirB).isDirectory()) {
    throw new Error('Both inputs must be screenshot directories.');
  }

  const filesA = new Set(walkPngs(dirA));
  const filesB = new Set(walkPngs(dirB));
  const all = Array.from(new Set([...filesA, ...filesB])).sort();
  if (!filesA.size || !filesB.size) throw new Error('Both screenshot directories must contain PNG files.');
  // Validate the complete inventory before producing any output.
  for (const rel of all) {
    if (!filesA.has(rel)) throw new Error(`Missing baseline image: ${rel}`);
    if (!filesB.has(rel)) throw new Error(`Missing after image: ${rel}`);
  }

  let totalChanged = 0;
  let filesChanged = 0;
  let sizeMismatches = 0;

  for (const rel of all) {
    const imgA = decodePng(fs.readFileSync(path.join(dirA, rel)));
    const imgB = decodePng(fs.readFileSync(path.join(dirB, rel)));
    if (imgA.width !== imgB.width || imgA.height !== imgB.height) {
      sizeMismatches++;
      filesChanged++;
      console.log(`${rel}: size mismatch ${imgA.width}x${imgA.height} vs ${imgB.width}x${imgB.height}`);
      continue;
    }
    const { changed, diff } = compareImages(imgA, imgB);
    if (changed > 0) {
      filesChanged++;
      totalChanged += changed;
      const outPath = path.join(outDir, rel);
      rejectSymlinks(path.dirname(outPath));
      fs.mkdirSync(path.dirname(outPath), { recursive: true });
      rejectSymlinks(path.dirname(outPath));
      fs.writeFileSync(outPath, encodePng(diff), { flag: 'wx' });
    }
    console.log(`${rel}: ${changed} changed pixels`);
  }
  console.log(`Total: ${totalChanged} changed pixels across ${all.length} images (${filesChanged} changed)`);
  console.log(`SUMMARY: ${all.length} pairs, ${filesChanged} changed files, ${totalChanged} changed pixels, ${sizeMismatches} size mismatches`);
  if (sizeMismatches) process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try { runCli(process.argv); }
  catch (error) { console.error(`Screenshot diff: ${error.message}`); process.exitCode = 1; }
}
