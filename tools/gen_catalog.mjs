/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 10:47:32 +08:00
 *
 * 把米家 APK 里自带的型号目录（assets/device_config/plugin_config_cn-zh_cn）
 * 压成模块能直接读的小文件：app/src/main/assets/catalog/devices.json.gz
 *
 * 为什么从这里取：米家「手动添加设备」列的就是这份数据 —— 16 个大类（cate_name）、
 * 183 个子类（subcategory_id）、每个型号的中文名（name）和官方产品图（icon_real）。
 * 拿它比自己去猜分类准得多，也不用联网。
 *
 * 品牌：米家这份数据里 brand_name 只有 73 条有值（共 12160），所以用「型号前缀」当品牌：
 * 前缀本来就是厂商账号（xiaomi / yeelink / lumi …），认得的映射成中文，认不得的直接显示前缀。
 * 每个子类里的型号按「品牌 → 设备名」排好序，界面拿到的顺序就是米家那种递增顺序。
 *
 * 用法：node tools/gen_catalog.mjs [apk路径]
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { execFileSync } from 'node:child_process';

const APK = process.argv[2] || 'hookapk/mijia_11.9.705.apk';
// 注意：扩展名不能叫 .gz —— aapt2 会把 .gz 资源自动解压并把后缀去掉，运行时按原路径就找不到了。
const OUT = 'app/src/main/assets/catalog/catalog.bin';

/* subcategory_id -> 中文子类名；顺序无所谓，没列到的会退回「其他设备」。*/
const SUBS = {
  1: '遥控器', 3: '摄像机', 4: '智能插座', 5: '手机', 8: '血压计', 9: '开发板',
  10: '儿童手表', 12: '网关', 13: '电动窗帘', 14: '路由器', 15: '电视', 16: '电视盒子',
  17: '灯具', 23: '种植机', 24: '风扇', 28: '智能马桶', 29: '早教机', 31: 'Wi-Fi 放大器',
  33: '冰箱', 39: '网络收音机', 40: '温湿度传感器', 42: '车载蓝牙', 43: '平衡车', 45: '机器人',
  47: '定位模块', 49: '微波炉', 50: '随身 Wi-Fi', 52: '温控器', 53: '电水壶', 54: '加湿器',
  55: '音箱', 57: '控制面板', 58: '旅行箱', 60: '植物监测仪', 61: '洗衣机', 62: '电动自行车',
  63: '体脂秤', 64: '驱蚊器', 65: '无线开关', 66: '净水器', 67: '咖啡机', 69: '闹钟',
  72: '冲奶机', 73: '电动牙刷', 74: '汽车', 76: '烟雾报警器', 77: '燃气报警器', 78: '门窗报警器',
  79: '体温计', 80: '空调伴侣', 85: '防丢器', 87: '电饭煲', 89: '睡眠监测', 93: '智能门锁',
  94: '走步机', 95: '车载空气净化器', 97: '行车记录仪', 98: '电动滑板车', 99: '温控鞋',
  109: '人体存在传感器', 111: '动静贴', 113: '扫地机器人', 114: '颈椎按摩仪', 115: '对讲机',
  116: '油烟机', 117: '门铃', 121: '按摩椅', 124: '照片打印机', 132: '电暖器', 135: '新风机',
  140: '电磁炉', 141: '燃气热水器', 142: '镜柜', 143: '手环', 144: '水位控制器', 149: '电压力锅',
  151: '智能后视镜', 153: '洗碗机', 154: '破壁机', 156: '猫砂盆', 157: '断路器',
  158: '门窗传感器', 159: '人体传感器', 160: '水浸传感器', 161: '智能开关', 162: '投影仪',
  163: '电视主机', 164: '晾衣机', 165: '蒸烤一体机', 166: '洗地机', 167: '门铃提醒器',
  169: '消毒柜', 171: '保温杯', 172: '香薰机', 175: '养生壶', 176: '轮滑鞋', 177: '宠物饮水机',
  178: '升降桌', 181: '机顶盒遥控', 184: 'Zigbee 设备', 185: '床垫', 186: 'DVD 遥控',
  189: '浴霸', 190: '光照传感器', 191: '厨房秤', 192: '电机控制器', 193: '电热毯', 194: '智能门',
  196: '跑步机', 198: '智能床', 200: '保管箱', 202: '集成灶', 204: '吸尘器', 205: '除湿机',
  207: '蒸汽眼罩', 208: '热敷宝', 209: '酒柜', 211: '宠物喂食器', 212: '足浴器', 215: '炒菜机',
  216: '烘鞋器', 217: '开窗器', 218: '凉霸', 219: '美容仪', 221: '食材净化机', 222: '软水机',
  225: '乳胶枕', 226: '鱼缸', 230: '电热毛巾架', 231: '杀菌灯', 232: '定时器', 233: 'HUD',
  234: '饮水机', 235: '干衣机', 236: '空气检测仪', 237: '空气净化器', 238: '空调',
  239: '空气炸锅', 242: '阀门控制器', 244: '智能沙发', 245: '蒸煮锅', 246: '插卡取电',
  247: '液晶黑板', 248: '补光灯', 249: '暖奶器', 250: '办公椅', 252: '动感单车', 256: '美容仪',
  257: '镭雕机', 259: '垃圾处理器', 260: '宠物陪伴球', 261: '跳绳', 265: '户外电源',
  267: '计时器', 270: '麦克风', 275: '卷帘门开关', 276: '剃须刀', 277: '吹风机',
  278: '制冰机', 279: '冲牙器', 283: '电蒸锅', 284: '电磁灶', 285: '吸奶器', 292: '控制伴侣',
  293: '氛围灯', 294: '压力传感器', 296: '儿童安全座椅', 297: '血糖仪', 300: '床头柜',
  301: '功放', 302: '感应龙头', 303: '健腹轮', 305: '哑铃', 308: '手机散热器', 309: '智能感知',
  310: '蓝牙音箱', 313: '充电桩', 315: '智能存储', 318: '壁挂炉', 320: '淋浴器', 322: '茶吧机',
  326: '笔记本', 327: '水质检测仪',
};

/* 型号前缀 -> 品牌。前缀是厂商账号，认得的高频厂牌翻成中文；
   没列到的直接用前缀本身当品牌（比硬塞进「其他」有信息量）。*/
const PREFIX_BRAND = {
  xiaomi: '小米', mijia: '米家', yeelink: 'Yeelight', viomi: '云米', yunmi: '云米',
  chunmi: '纯米', zhimi: '智米', chuangmi: '创米', lumi: '绿米', dreame: '追觅',
  roborock: '石头', rockrobo: '石头', dooya: '杜亚', leishi: '雷士', opple: '欧普',
  aupu: '奥普', bull: '公牛', philips: '飞利浦', honyar: '鸿雁', loock: '鹿客',
  bshcn: '博西家电', linp: '领普',
};

/* 品牌名可能是 URL 编码 / \uXXXX 转义过的脏数据，能还原就还原。*/
function cleanBrand(raw) {
  const b = (raw || '').trim();
  if (!b) return '';
  if (/^(u[0-9a-f]{4})+$/i.test(b)) {
    return b.split(/(?=u[0-9a-f]{4})/i).map((x) => String.fromCharCode(parseInt(x.slice(1), 16))).join('');
  }
  try {
    return decodeURIComponent(b);
  } catch {
    return b;
  }
}

function brandOf(e) {
  const model = (e.model || '').trim();
  const prefix = model.split('.')[0] || '';
  return cleanBrand(e.brand_name) || PREFIX_BRAND[prefix] || prefix || '其他';
}

const collator = new Intl.Collator('zh', { numeric: true });

const raw = execFileSync('unzip', ['-p', APK, 'assets/device_config/plugin_config_cn-zh_cn'],
    { maxBuffer: 256 * 1024 * 1024 }).toString('utf8');
const src = JSON.parse(raw);
const list = src.result.devices.list;
const ts = src.result.devices.last_modify || 0;

const cats = new Map();
for (const e of list) {
  const model = (e.model || '').trim();
  const name = (e.name || '').trim();
  if (!model || !name) continue;
  const cat = (e.cate_name || '其他').trim();
  const sub = SUBS[e.subcategory_id] || '其他设备';
  const icon = (e.icon_real || '').trim();
  const brand = brandOf(e);
  // 米家目录里没有「发布日期」，verified_time 是最早的官方时间戳，当上市时间用
  const releasedAt = e.verified_time || 0;

  if (!cats.has(cat)) cats.set(cat, new Map());
  const subs = cats.get(cat);
  if (!subs.has(sub)) subs.set(sub, []);
  // [型号, 中文名, 产品图, 品牌, 上市时间(秒)]
  subs.get(sub).push([model, name, icon, brand, releasedAt]);
}

/* 品牌 → 设备名 递增；之后界面无论怎么拼，顺序都是这个。*/
const byBrandThenName = (a, b) => collator.compare(a[3], b[3]) || collator.compare(a[1], b[1]);
for (const subs of cats.values()) for (const items of subs.values()) items.sort(byBrandThenName);

const outCats = [...cats.entries()]
  .map(([name, subs]) => ({
    n: name,
    count: [...subs.values()].reduce((a, b) => a + b.length, 0),
    subs: [...subs.entries()]
      .map(([sn, items]) => ({ n: sn, items }))
      .sort((a, b) => b.items.length - a.items.length),
  }))
  .sort((a, b) => b.count - a.count)
  .map(({ n, subs }) => ({ n, subs }));

const payload = JSON.stringify({ ts, cats: outCats });fs.mkdirSync(path.dirname(OUT), { recursive: true });
const gz = zlib.gzipSync(Buffer.from(payload, 'utf8'), { level: 9 });
fs.writeFileSync(OUT, gz);

const total = outCats.reduce((a, c) => a + c.subs.reduce((x, s) => x + s.items.length, 0), 0);
console.log(`cats=${outCats.length} devices=${total} raw=${(payload.length / 1024).toFixed(0)}KB ` +
    `gz=${(gz.length / 1024).toFixed(0)}KB -> ${OUT}`);
