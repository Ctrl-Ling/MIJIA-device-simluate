// Author: Ctrl-Ling
// Last Modified By: Ctrl-Ling
// Last Modified At: 2026-11-09 18:50:00 +08:00
/**
 * dexkit.mjs — 纯 Node 的 DEX 结构解析器（不反编译方法体，只抽符号表）
 * 用途：在 240MB 的米家 APK 上，用几百毫秒拿到「类名 + 方法名 + 完整原型 + 字段」，
 *       这就是写 Xposed/LSPosed hook 需要的全部信息（hook 只要类名+方法名+参数类型）。
 *
 * 用法:
 *   node dexkit.mjs list   <regex>                 列出匹配的类（默认全部）
 *   node dexkit.mjs members <全类名正则>            打印字段与方法签名
 *   node dexkit.mjs method <方法名正则>             反查：哪些类声明了这个名字的方法
 *   node dexkit.mjs string <字符串正则>             在字符串池里搜（表名/URL/特征串）
 *   node dexkit.mjs strings <类名>                  某类引用的字符串（近似：按 class_data 顺序不可靠，跳过）
 *   node dexkit.mjs count                           统计各 dex 的类数量
 *
 * 输入默认取 ./dex/classes*.dex，可用 --dex=<路径或目录> 覆盖。
 */
import fs from 'node:fs'
import path from 'node:path'

const argv = process.argv.slice(2)
const cmd = argv[0]
const positional = argv.slice(1).filter((a) => !a.startsWith('--'))
const dexArg = argv.find((a) => a.startsWith('--dex='))
let inputs = []
if (dexArg) {
  const p = dexArg.slice(6)
  const st = fs.statSync(p)
  inputs = st.isDirectory()
    ? fs.readdirSync(p).filter((f) => f.endsWith('.dex')).map((f) => path.join(p, f))
    : [p]
} else {
  const dir = path.resolve(import.meta.dirname, 'dex')
  if (fs.existsSync(dir)) inputs = fs.readdirSync(dir).filter((f) => f.endsWith('.dex')).map((f) => path.join(dir, f))
}
inputs.sort()
if (!inputs.length) { console.error('no .dex found; pass --dex=...'); process.exit(1) }

/* ---------- dex 解析 ---------- */
class Dex {
  constructor(file) {
    this.file = path.basename(file)
    this.buf = fs.readFileSync(file)
    const b = this.buf
    const magic = b.readUInt32LE(0)
    if (magic !== 0x0a786564 && b.toString('ascii', 0, 3) !== 'cdx') throw new Error('not a dex: ' + file)
    this.headerSize = b.readUInt32LE(36)
    this.endian = b.readUInt32LE(40)
    if (this.endian !== 0x12345678) throw new Error('endian unsupported')
    this.stringIdsSize = b.readUInt32LE(56); this.stringIdsOff = b.readUInt32LE(60)
    this.typeIdsSize = b.readUInt32LE(64); this.typeIdsOff = b.readUInt32LE(68)
    this.protoIdsSize = b.readUInt32LE(72); this.protoIdsOff = b.readUInt32LE(76)
    this.fieldIdsSize = b.readUInt32LE(80); this.fieldIdsOff = b.readUInt32LE(84)
    this.methodIdsSize = b.readUInt32LE(88); this.methodIdsOff = b.readUInt32LE(92)
    this.classDefsSize = b.readUInt32LE(96); this.classDefsOff = b.readUInt32LE(100)
    this._strings = new Array(this.stringIdsSize)
    this._types = null
    this._protos = null
  }
  uleb(off) {
    let r = 0, shift = 0, i = off
    for (;;) {
      const b = this.buf[i++]
      r |= (b & 0x7f) << shift
      if ((b & 0x80) === 0) break
      shift += 7
    }
    return r >>> 0
  }
  ulebEnd(off) { const n = this.uleb(off); return off + this.ulebLen(n, off) }
  ulebLen(v, off) { let len = 0, i = off; for (;;) { const b = this.buf[i++]; len++; if ((b & 0x80) === 0) break } return len }
  string(n) {
    if (n >= this.stringIdsSize) return '<bad str>'
    const cached = this._strings[n]
    if (cached !== undefined) return cached
    const off = this.buf.readUInt32LE(this.stringIdsOff + n * 4)
    const dataOff = off + this.ulebLen(0, off) // skip utf16_size
    const end = this.buf.indexOf(0, dataOff)
    const s = this.buf.toString('utf8', dataOff, end)
    this._strings[n] = s
    return s
  }
  types() {
    if (this._types) return this._types
    this._types = new Array(this.typeIdsSize)
    for (let i = 0; i < this.typeIdsSize; i++)
      this._types[i] = this.string(this.buf.readUInt32LE(this.typeIdsOff + i * 4))
    return this._types
  }
  type(i) { return this.types()[i] || 'void' }
  proto(i) {
    if (!this._protos) {
      this._protos = new Array(this.protoIdsSize)
      for (let k = 0; k < this.protoIdsSize; k++) {
        const off = this.protoIdsOff + k * 12 // proto_id_item 是定长内联数组
        const ret = this.buf.readUInt32LE(off + 4)
        const paramsOff = this.buf.readUInt32LE(off + 8)
        const ps = []
        if (paramsOff) {
          const size = this.buf.readUInt32LE(paramsOff)
          for (let j = 0; j < size; j++)
            ps.push(this.type(this.buf.readUInt16LE(paramsOff + 4 + j * 2)))
        }
        this._protos[k] = { ret: ret === 0xffffffff ? 'void' : this.type(ret), params: ps }
      }
    }
    return this._protos[i]
  }
  field(i) {
    const off = this.fieldIdsOff + i * 8
    return {
      cls: this.type(this.buf.readUInt16LE(off)),
      type: this.type(this.buf.readUInt16LE(off + 2)),
      name: this.string(this.buf.readUInt32LE(off + 4))
    }
  }
  method(i) {
    const off = this.methodIdsOff + i * 8
    const p = this.proto(this.buf.readUInt16LE(off + 2))
    return {
      cls: this.type(this.buf.readUInt16LE(off)),
      name: this.string(this.buf.readUInt32LE(off + 4)),
      ret: p.ret, params: p.params
    }
  }
  classes() {
    if (this._classes) return this._classes
    this._classes = []
    for (let i = 0; i < this.classDefsSize; i++) {
      const off = this.classDefsOff + i * 32
      const clsIdx = this.buf.readUInt32LE(off)
      const flags = this.buf.readUInt32LE(off + 4)
      const superIdx = this.buf.readUInt32LE(off + 8)
      const ifOff = this.buf.readUInt32LE(off + 12)
      const cdOff = this.buf.readUInt32LE(off + 24)
      const ifaces = []
      if (ifOff) {
        const n = this.buf.readUInt32LE(ifOff)
        for (let k = 0; k < n; k++) ifaces.push(this.type(this.buf.readUInt16LE(ifOff + 4 + k * 2)))
      }
      const rec = {
        dex: this.file, desc: this.type(clsIdx),
        name: jname(this.type(clsIdx)),
        flags, super: superIdx === 0xffffffff ? null : this.type(superIdx), ifaces,
        fields: [], methods: []
      }
      if (cdOff) {
        let o = cdOff
        const next = () => {
          const v = this.uleb(o)
          let i = o
          for (;;) { const b = this.buf[i++]; if ((b & 0x80) === 0) break }
          o = i
          return v
        }
        const sizes = [next(), next(), next(), next()]
        let diff = 0
        for (let kind = 0; kind < 2; kind++) {
          diff = 0
          for (let k = 0; k < sizes[kind]; k++) {
            const d = this.uleb(o); o += this.ulebLen(d, o)
            const fl = this.uleb(o); o += this.ulebLen(fl, o)
            diff += d
            const f = this.field(diff)
            rec.fields.push({ name: f.name, type: f.type, flags: fl, static: kind === 0 })
          }
        }
        for (let kind = 0; kind < 2; kind++) {
          diff = 0
          for (let k = 0; k < sizes[2 + kind]; k++) {
            const d = this.uleb(o); o += this.ulebLen(d, o)
            const fl = this.uleb(o); o += this.ulebLen(fl, o)
            const co = this.uleb(o); o += this.ulebLen(co, o)
            diff += d
            const m = this.method(diff)
            rec.methods.push({ name: m.name, ret: m.ret, params: m.params, flags: fl, direct: kind === 0, hasCode: co !== 0 })
          }
        }
      }
      this._classes.push(rec)
    }
    return this._classes
  }
}

function jname(desc) {
  // "Lcom/foo/Bar;" -> "com.foo.Bar" ; "[I" -> "int[]"
  let arr = 0
  while (desc.startsWith('[')) { arr++; desc = desc.slice(1) }
  let base = desc
  if (base.length === 1) base = { V: 'void', Z: 'boolean', B: 'byte', S: 'short', J: 'long', F: 'float', D: 'double', C: 'char', I: 'int' }[base] || base
  else if (base.startsWith('L') && base.endsWith(';')) base = base.slice(1, -1).replace(/\//g, '.')
  return base + '[]'.repeat(arr)
}
function accStr(f) {
  const m = [[0x1, 'public'], [0x2, 'private'], [0x4, 'protected'], [0x8, 'static'], [0x10, 'final'], [0x400, 'abstract'], [0x100, 'native'], [0x200, 'interface'], [0x40, 'bridge'], [0x10000, 'declaredSync']]
  return m.filter(([b]) => f & b).map(([, s]) => s).join(' ')
}

const dexes = inputs.map((f) => new Dex(f))
const all = dexes.flatMap((d) => d.classes())
const byName = new Map()
for (const c of all) if (!byName.has(c.name)) byName.set(c.name, c)

/* ---------- commands ---------- */
const re = positional[0] ? new RegExp(positional[0]) : null
switch (cmd) {
  case 'count':
    for (const d of dexes) console.log(d.file.padEnd(16), d.classes().length, 'classes,', d.stringIdsSize, 'strings')
    console.log('TOTAL declared (dedup):', byName.size)
    break
  case 'list': {
    const out = [...byName.keys()].filter((n) => !re || re.test(n)).sort()
    console.log(out.join('\n'))
    console.error(`${out.length} classes`)
    break
  }
  case 'members': {
    const hits = [...byName.values()].filter((c) => re && re.test(c.name)).sort((a, b) => a.name.localeCompare(b.name))
    if (hits.length > 30) console.error(`${hits.length} matched — narrow the regex (showing first 30)`)
    for (const c of hits.slice(0, 30)) {
      console.log(`\n\`\`\`${c.dex}`)
      console.log(`${accStr(c.flags)} ${c.name}${c.super ? ' extends ' + jname(c.super) : ''}${c.ifaces.length ? ' implements ' + c.ifaces.map(jname).join(', ') : ''}`)
      for (const f of c.fields) console.log(`    F ${accStr(f.flags)} ${jname(f.type)} ${f.name}`)
      for (const m of c.methods) console.log(`    M ${accStr(m.flags)} ${jname(m.ret)} ${m.name}(${m.params.map(jname).join(', ')})${m.hasCode ? '' : '  [no-code]'}`)
    }
    console.error(`\n${hits.length} classes shown: ${hits.map((h) => h.name).join(', ')}`)
    break
  }
  case 'method': {
    const rows = []
    for (const c of byName.values())
      for (const m of c.methods)
        if (re && re.test(m.name)) rows.push(`${c.name}#${m.name}(${m.params.map(jname).join(', ')}) -> ${jname(m.ret)}  [${accStr(m.flags)}] ${c.dex}`)
    rows.sort()
    console.log(rows.join('\n'))
    console.error(rows.length + ' methods')
    break
  }
  case 'field': {
    const rows = []
    for (const c of byName.values())
      for (const f of c.fields)
        if (re && re.test(f.name)) rows.push(`${c.name}.${f.name} : ${jname(f.type)}  [${accStr(f.flags)}] ${c.dex}`)
    rows.sort()
    console.log(rows.join('\n'))
    console.error(rows.length + ' fields')
    break
  }
  case 'impl': {
    // 谁继承/实现了目标类型（按 simple 名或全名正则匹配 super_class / interfaces）
    const rows = []
    for (const c of byName.values()) {
      const sup = c.super ? jname(c.super) : ''
      const ifs = c.ifaces.map(jname)
      if ((re.test(sup) || ifs.some((i) => re.test(i))) && !re.test(c.name))
        rows.push(`${c.name}  extends ${sup || '-'}  implements ${ifs.join(', ') || '-'}  [${accStr(c.flags)}] ${c.dex}`)
      else if (re.test(sup) || ifs.some((i) => re.test(i)))
        rows.push(`${c.name}  extends ${sup || '-'}  implements ${ifs.join(', ') || '-'}  [${accStr(c.flags)}] ${c.dex}`)
    }
    rows.sort()
    console.log(rows.join('\n'))
    console.error(rows.length + ' implementers')
    break
  }
  case 'string': {
    const set = new Set()
    for (const d of dexes)
      for (let i = 0; i < d.stringIdsSize; i++) {
        const s = d.string(i)
        if (re && re.test(s)) set.add(s)
      }
    const out = [...set].sort()
    console.log(out.join('\n'))
    console.error(out.length + ' strings')
    break
  }
  default:
    console.log(`dexkit commands: count | list <re> | members <re> | method <re> | field <re> | string <re> [--dex=path]`)
}
