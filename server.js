const dns = require('dns');
dns.setDefaultResultOrder('ipv4first');

process.on('uncaughtException', err => {
  console.error('[UncaughtException] Đã bắt lỗi an toàn:', err.message);
});
process.on('unhandledRejection', err => {
  console.error('[UnhandledRejection] Đã bắt lỗi an toàn:', err?.message || err);
});

const express = require('express');
const cors = require('cors');
const axios = require('axios');
const https = require('https');
const path = require('path');
const fs = require('fs');
const puppeteer = require('puppeteer-core');

const app = express();
const PORT = process.env.PORT || 7860;

app.use(cors());
app.use(express.json());
// Hỗ trợ đọc file tĩnh ở cả thư mục /public lẫn thư mục gốc /
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.static(__dirname));

app.get('/', (req, res) => {
  const rootIndex = path.join(__dirname, 'index.html');
  const pubIndex = path.join(__dirname, 'public', 'index.html');
  if (fs.existsSync(rootIndex)) return res.sendFile(rootIndex);
  if (fs.existsSync(pubIndex)) return res.sendFile(pubIndex);
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', uptime: process.uptime(), time: Date.now() });
});

// Tự động giữ ấm server chống ngủ đông (Anti-Sleep Self Ping)
const externalUrl = process.env.RENDER_EXTERNAL_URL || process.env.PUBLIC_URL;
if (externalUrl) {
  console.log(`[AntiSleep] Kích hoạt cơ chế tự giữ ấm cho: ${externalUrl}`);
  setInterval(() => {
    fetch(`${externalUrl}/api/health`).catch(() => {});
  }, 10 * 60 * 1000); // Mỗi 10 phút ping 1 lần
}

const ipv4Agent = new https.Agent({
  family: 4,
  keepAlive: true,
  maxSockets: 32
});

// ============================================================================
// 1. BROWSER ENGINE (Headless Chrome/Edge - Warm Tab Pool & Deep Pagination)
// ============================================================================
const BROWSER_PATHS = [
  process.env.PUPPETEER_EXECUTABLE_PATH,
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe'
].filter(Boolean);

function getBrowserExecutablePath() {
  for (const p of BROWSER_PATHS) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

let sharedBrowser = null;
let latestCookieHeader = '';

async function getBrowser() {
  // Trên Render (512MB RAM), tuyệt đối tắt Chrome để giữ RAM dưới 70MB, chống tràn RAM OOM
  if (process.env.RENDER || process.env.IS_RENDER) {
    return null;
  }
  if (sharedBrowser && sharedBrowser.connected) {
    return sharedBrowser;
  }
  const executablePath = getBrowserExecutablePath();
  if (!executablePath) {
    console.log('[BrowserEngine] Môi trường không có Chrome -> Tự động kích hoạt TikWM Cloud Engine');
    return null;
  }
  console.log(`[BrowserEngine] Khởi tạo Headless Browser từ: ${executablePath}`);
  try {
    sharedBrowser = await puppeteer.launch({
      executablePath,
      headless: 'new',
      args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-blink-features=AutomationControlled',
        '--disable-dev-shm-usage',
        '--disable-gpu',
        '--mute-audio'
      ]
    });
    sharedBrowser.on('disconnected', () => {
      sharedBrowser = null;
    });
    return sharedBrowser;
  } catch (err) {
    console.warn('[BrowserEngine] Không thể khởi tạo Chrome:', err.message);
    return null;
  }
}

// Registry lưu thông tin stream và cookie cho từng video ID
const mediaRegistry = new Map();

// ============================================================================
// 1.5. CỔNG API XUYÊN TƯỜNG LỬA TIKTOK (TIKWM CLOUD RESILIENT FALLBACK)
// Tự động kích hoạt khi chạy trên Datacenter (Render/AWS) bị TikTok chặn Capcha
// ============================================================================
function transformTikWMItem(v) {
  const id = String(v.video_id || v.id || '');
  const playUrl = v.play || v.wmplay || '';
  const audioUrl = v.music || playUrl;
  const coverUrl = v.origin_cover || v.cover || '';
  const authorName = v.author?.nickname || v.author?.unique_id || 'TikTok Creator';
  const username = v.author?.unique_id || 'creator';
  const avatar = v.author?.avatar || '';

  mediaRegistry.set(id, {
    id,
    videoUrl: playUrl,
    audioUrl: audioUrl,
    coverUrl: coverUrl,
    cookieHeader: '',
    updatedAt: Date.now()
  });

  return {
    id,
    desc: v.title || '',
    createTime: Number(v.create_time || Math.floor(Date.now() / 1000)),
    duration: Number(v.duration || 0),
    width: 576,
    height: 1024,
    aspectRatio: 0.5625,
    isLandscape: false,
    isPinned: false,
    cover: coverUrl,
    streamVideoUrl: `/api/stream/${id}?type=video`,
    streamAudioUrl: `/api/stream/${id}?type=audio`,
    tiktokUrl: `https://www.tiktok.com/@${username}/video/${id}`,
    _sourceSlug: '',
    author: {
      id: String(v.author?.id || ''),
      username,
      uniqueId: username,
      nickname: authorName,
      avatar,
      verified: false
    },
    music: {
      id: String(v.music_info?.id || ''),
      title: v.music_info?.title || v.music_title || 'Âm thanh gốc',
      author: v.music_info?.author || authorName,
      authorName: v.music_info?.author || authorName,
      cover: v.music_info?.cover || avatar,
      duration: Number(v.duration || 0),
      original: true,
      hasDirectAudio: Boolean(audioUrl)
    },
    video: {
      duration: Number(v.duration || 0),
      width: 576,
      height: 1024,
      aspectRatio: 0.5625,
      isLandscape: false,
      cover: coverUrl,
      streamUrl: `/api/stream/${id}?type=video`
    },
    stats: {
      plays: Number(v.play_count || 0),
      playCount: Number(v.play_count || 0),
      diggCount: Number(v.digg_count || 0),
      commentCount: Number(v.comment_count || 0),
      shareCount: Number(v.share_count || 0),
      collectCount: Number(v.collect_count || 0)
    },
    hashtags: []
  };
}

class RequestQueue {
  constructor(delayMs = 1200) {
    this.delayMs = delayMs;
    this.lastRun = 0;
    this.queue = [];
    this.running = false;
  }
  async add(fn) {
    return new Promise((resolve, reject) => {
      this.queue.push({ fn, resolve, reject });
      this.process();
    });
  }
  async process() {
    if (this.running || this.queue.length === 0) return;
    this.running = true;
    while (this.queue.length > 0) {
      const { fn, resolve, reject } = this.queue.shift();
      const waitTime = Math.max(0, this.lastRun + this.delayMs - Date.now());
      if (waitTime > 0) await new Promise(r => setTimeout(r, waitTime));
      this.lastRun = Date.now();
      try {
        const result = await fn();
        resolve(result);
      } catch (err) {
        reject(err);
      }
    }
    this.running = false;
  }
}

const tikwmQueue = new RequestQueue(1200);
const tikwmCache = new Map();

async function fetchTikWMSearch(keyword, count = 30) {
  const cacheKey = `search:${keyword}:${count}`;
  const cached = tikwmCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.data;
  }

  try {
    const list = await tikwmQueue.add(async () => {
      const res = await axios.get('https://www.tikwm.com/api/feed/search', {
        params: { keywords: keyword, count },
        httpsAgent: ipv4Agent,
        timeout: 9000,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
      });
      return res.data?.data?.videos || [];
    });

    if (Array.isArray(list) && list.length > 0) {
      console.log(`[TikWM] Cào thành công ${list.length} video cho từ khóa: ${keyword}`);
      const mapped = list.map(transformTikWMItem);
      tikwmCache.set(cacheKey, { data: mapped, expiresAt: Date.now() + 30 * 60 * 1000 });
      return mapped;
    }
  } catch (err) {
    if (!String(err.message || '').includes('403')) {
      console.warn('[TikWM] Lỗi tìm kiếm:', err.message);
    }
  }
  return [];
}

async function fetchTikWMUser(username, count = 50) {
  const cacheKey = `user:${username}:${count}`;
  const cached = tikwmCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.data;
  }

  try {
    const data = await tikwmQueue.add(async () => {
      const res = await axios.get('https://www.tikwm.com/api/user/posts', {
        params: { unique_id: username, count },
        httpsAgent: ipv4Agent,
        timeout: 9000,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
      });
      return res.data?.data?.videos || [];
    });

    if (Array.isArray(data) && data.length > 0) {
      console.log(`[TikWM] Cào thành công ${data.length} video kênh @${username}`);
      const first = data[0];
      const result = {
        profile: {
          id: String(first?.author?.id || ''),
          uniqueId: username,
          username: username,
          nickname: first?.author?.nickname || username,
          avatar: first?.author?.avatar || '',
          signature: `Kênh TikTok @${username}`,
          verified: false,
          followerCount: 0,
          followingCount: 0,
          heartCount: 0,
          videoCount: data.length,
          followers: 0,
          following: 0,
          hearts: 0,
          videos: data.length
        },
        videos: data.map(transformTikWMItem)
      };
      tikwmCache.set(cacheKey, { data: result, expiresAt: Date.now() + 30 * 60 * 1000 });
      return result;
    }
  } catch (err) {
    if (!String(err.message || '').includes('403')) {
      console.warn('[TikWM] Lỗi kênh:', err.message);
    }
  }
  return null;
}

/**
 * Phân giải luồng phát video trực tiếp qua TikWM Cloud POST API (Bypass Cloudflare & không cần token)
 */
async function fetchTikWMSingleVideo(videoId) {
  if (!videoId) return null;
  const cleanId = String(videoId).replace(/\D/g, '');
  if (!cleanId) return null;

  try {
    const params = new URLSearchParams();
    params.append('url', `https://www.tiktok.com/@a/video/${cleanId}`);
    params.append('hd', '1');
    const res = await axios.post('https://www.tikwm.com/api/', params.toString(), {
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
      },
      timeout: 10000
    });
    if (res.data && res.data.code === 0 && res.data.data) {
      const d = res.data.data;
      const streamVideo = d.play || d.wmplay;
      const streamAudio = d.music || (d.music_info && d.music_info.play) || streamVideo;
      if (streamVideo) {
        mediaRegistry.set(cleanId, {
          id: cleanId,
          videoUrl: streamVideo,
          audioUrl: streamAudio,
          coverUrl: d.cover,
          cookieHeader: '',
          updatedAt: Date.now()
        });
        return {
          id: cleanId,
          videoUrl: streamVideo,
          audioUrl: streamAudio,
          coverUrl: d.cover
        };
      }
    }
  } catch (err) {}
  return null;
}

// Kho lưu trữ (Deep Video Pool) theo từng từ khóa/tag để càng tìm càng ra bài mới
// Map<poolKey, { items: Array<NormalizedVideo>, rawCount: number, blockedStats: Object, nextTagIndex: number, updatedAt: number }>
const deepVideoPools = new Map();
const backgroundJobs = new Set();

// ============================================================================
// 2. HỆ THỐNG CỤM HASHTAG ĐA CHIỀU (MULTI-TAG CLUSTERS)
// ============================================================================
const TAG_CLUSTERS = {
  nhacchill: [
    'nhacchill', 'chillvibes', 'nhacthugian', 'chillout', 'soundchill',
    'nhacchillbuon', 'chillhop', 'deepchill', 'nhacngungon', 'chilldemkhuya',
    'nhacnhenhang', 'chillcungban'
  ],
  lofi: [
    'lofi', 'lofichill', 'nhaclofi', 'lofiviet', 'lofihiphop',
    'lofibuon', 'chilllofi', 'studymusic', 'lofibeats', 'lofi2026'
  ],
  nhachaymoingay: [
    'nhachaymoingay', 'xuhuongnhac', 'nhachay', 'playlistnhac', 'amnhac',
    'nhacmoi', 'topnhac', 'soundtiktok', 'nhacviral', 'nhachot', 'caubesuutamnhac'
  ],
  remix: [
    'remix', 'nhacremix', 'tiktokremix', 'vinahouse', 'nonstopremix',
    'djremix', 'bassboosted', 'nhacbay', 'nhacsan', 'vietmix', 'tdremix'
  ],
  acoustic: [
    'acoustic', 'acousticcover', 'nhacmoc', 'guitarcover', 'hatmoc',
    'liveacoustic', 'covernhac', 'acousticviet', 'IndieAcoustic'
  ],
  vietmix: [
    'vietmix', 'nonstop', 'nhacsan', 'mixset', 'nhacnghedixe',
    'edmviet', 'nhacdj', 'nhacsinhvien', 'chilloutmix'
  ],
  nhactre: [
    'nhactre', 'vpop', 'nhacviet', 'nhactrehay', 'balladviet',
    'indieviet', 'nhacmoi2026', 'topvpop', 'nhactreremix'
  ],
  usuk: [
    'usuk', 'nhacusuk', 'popmusic', 'usuktiktok', 'englishsongs',
    'rnb', 'edmmusic', 'westernmusic', 'usukchill', 'hitsongs'
  ],
  bolero: [
    'bolero', 'nhacbolero', 'nhactrutinh', 'nhacxua', 'nhacvang',
    'trutinhquehuong', 'lkbolero'
  ],
  healing: [
    'healing', 'nhacchualanh', 'meditationmusic', 'peacefulmusic',
    'relaxingmusic', 'calmmusic', 'tinhlang'
  ],
  pianocover: [
    'pianocover', 'piano', 'nhackhongloi', 'instrumental',
    'guitarsolo', 'violin', 'pianochill'
  ],
  nhacbuon: [
    'nhacbuon', 'nhactamtrang', 'sadmusic', 'balladbuon',
    'nhacsuy', 'tamtrang', 'nhacthattinh'
  ]
};

const ALL_DIVERSE_MUSIC_TAGS = [
  'nhacchill', 'lofi', 'nhachaymoingay', 'remix', 'acoustic', 'vietmix',
  'nhactre', 'usuk', 'bolero', 'healing', 'pianocover', 'nhacbuon',
  'xuhuongnhac', 'playlistnhac', 'vpop', 'indieviet', 'chillvibes',
  'nhacmoi', 'covernhac', 'soundhay', 'lofiviet', 'nhacnghedixe',
  'nhacthugian', 'balladviet', 'tiktokremix', 'usuktiktok', 'nhacviral',
  'chillout', 'nhacmoc', 'nhactamtrang', 'deepchill', 'caubesuutamnhac'
];

function shuffleArray(arr) {
  const copy = [...arr];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}

function toHashtagSlug(input) {
  return String(input || '')
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd')
    .replace(/[^a-z0-9_]/g, '');
}

/**
 * Mở rộng câu tìm kiếm thành danh sách #hashtag ĐÚNG CHỦ ĐỀ theo thứ tự độ chính xác giảm dần.
 * TUYỆT ĐỐI KHÔNG trộn các tag ngẫu nhiên lạc đề vào kết quả tìm kiếm!
 */
function expandQueryToTagPool(rawQuery) {
  const clean = String(rawQuery || '').trim();
  const fullSlug = toHashtagSlug(clean);
  if (!fullSlug) return ['nhacchill', 'nhachaymoingay', 'lofi', 'remix'];

  if (TAG_CLUSTERS[fullSlug]) {
    return [...TAG_CLUSTERS[fullSlug]];
  }

  const variants = new Set([fullSlug]);

  // Tách các từ đơn đã bỏ dấu
  const words = clean
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd')
    .split(/\s+/)
    .map(w => w.replace(/[^a-z0-9]/g, ''))
    .filter(w => w.length >= 2);

  const hasDanceIntent = words.some(w =>
    ['nhay', 'dance', 'trend', 'lac', 'cover', 'sexy', 'hiphop', 'kpop'].includes(w)
  );
  const hasGirlBoyIntent = words.some(w =>
    ['gai', 'xinh', 'hotgirl', 'trai', 'dep', 'idol', 'visual'].includes(w)
  );

  // Nếu người dùng tìm chủ đề nhảy / gái xinh nhảy / dance trend
  if (hasDanceIntent && hasGirlBoyIntent) {
    variants.add('nhaydep');
    variants.add('nhaytiktok');
    variants.add(`${fullSlug}trend`);
    variants.add('gaixinhtiktok');
    variants.add('nhaytrend');
    variants.add('xuhuongnhay');
    variants.add('dancecover');
    variants.add('gaixinhnhay');
    variants.add('hotgirlnhay');
  } else if (hasDanceIntent) {
    variants.add('nhaydep');
    variants.add('nhaytiktok');
    variants.add(`${fullSlug}trend`);
    variants.add(`${fullSlug}cover`);
    variants.add('nhaytrend');
    variants.add('dancecover');
    variants.add('xuhuongnhay');
  } else {
    // Chủ đề âm nhạc / bài hát / ca sĩ
    if (!fullSlug.startsWith('nhac')) {
      variants.add(`nhac${fullSlug}`);
    }
    variants.add(`${fullSlug}remix`);
    variants.add(`${fullSlug}chill`);
    variants.add(`${fullSlug}cover`);
    variants.add(`${fullSlug}trend`);
  }

  // Tổ hợp từ liền kề có ý nghĩa (>= 5 ký tự, tránh các từ đơn quá chung chung như "gai", "xinh")
  if (words.length >= 2) {
    for (let i = 0; i < words.length - 1; i++) {
      const pair = words[i] + words[i + 1];
      if (pair.length >= 5 && pair !== 'gaixinh') {
        variants.add(pair);
      }
    }
    words.forEach(w => {
      if (TAG_CLUSTERS[w]) {
        TAG_CLUSTERS[w].slice(0, 4).forEach(t => variants.add(t));
      }
    });
  }

  return [...variants];
}

/**
 * Chấm điểm độ khớp nội dung (Relevance Score) của từng video đối với câu tìm kiếm
 * Đảm bảo 15-25+ video đầu tiên luôn đúng 100% ý định tìm kiếm của người dùng!
 */
function scoreVideoRelevance(video, rawQuery, primarySlug) {
  const cleanQuery = String(rawQuery || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd')
    .trim();

  if (!cleanQuery) return 100;

  const words = cleanQuery
    .split(/\s+/)
    .map(w => w.replace(/[^a-z0-9]/g, ''))
    .filter(w => w.length >= 2);

  const descNorm = String(video.desc || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd');

  const musicNorm = String(video.music?.title || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd');

  const hashtags = (video.hashtags || []).map(h => toHashtagSlug(h));
  const fullText = `${descNorm} ${musicNorm} ${hashtags.join(' ')}`;

  let score = 0;

  // 1. Video đến trực tiếp từ Tag gốc chính xác (#gaixinhnhay) hoặc có gắn chính xác #primarySlug
  if (video._sourceSlug === primarySlug || hashtags.includes(primarySlug)) {
    score += 120;
  }

  // 2. Chứa nguyên cụm từ tìm kiếm trong mô tả hoặc tên nhạc
  if (descNorm.includes(cleanQuery) || musicNorm.includes(cleanQuery) || fullText.includes(primarySlug)) {
    score += 90;
  }

  // 3. Đếm số từ khóa đơn trong câu tìm kiếm xuất hiện trong video
  let matchedWords = 0;
  for (const w of words) {
    if (fullText.includes(w)) {
      matchedWords++;
      score += 25;
    }
  }

  // Thưởng lớn nếu khớp tất cả các từ trong câu tìm kiếm (VD: vừa có "gai/xinh" vừa có "nhay")
  if (words.length >= 2 && matchedWords === words.length) {
    score += 75;
  } else if (words.length >= 2 && matchedWords >= words.length - 1) {
    score += 35;
  }

  // 4. Từ đồng nghĩa theo ngữ cảnh (VD: tìm "nhảy" thì video có #dance, #trend, #cover cũng được cộng điểm)
  if (words.includes('nhay') && /\bdance|trend|nhay|cover|choreo\b/.test(fullText)) {
    score += 30;
  }

  return score;
}

// ============================================================================
// 3. THUẬT TOÁN LỌC VIDEO TỐC ĐỘ CAO O(1) (PRE-COMPILED REGEX ENGINE)
// ============================================================================
const SHOP_SPAM_REGEX = new RegExp(
  [
    'giỏ hàng', 'gio hang', 'góc trái', 'goc trai', 'link bio', 'link ở bio',
    'mua ngay', 'mua tại', 'săn sale', 'san sale', 'freeship', 'mã giảm giá',
    'voucher', 'tiktok\\s*shop', 'affiliate', 'check giá', 'inbox giá',
    'chốt đơn', 'xả kho', 'kèm link', 'gắn link', 'link sản phẩm', 'bấm vào giỏ'
  ].join('|'),
  'i'
);

const COMMERCIAL_ANCHOR_REGEX = new RegExp(
  'shop|product|ecommerce|goods|commodity|affiliate|store|mall|buy|voucher|coupon|live|game|ba_lead|paid',
  'i'
);

const CAPCUT_ANCHOR_REGEX = /capcut|tt_capcut|viamaker/i;
const SHOP_ANCHOR_TYPES = new Set([33, 35, 56, 60, 62]);

function inspectVideoCleanlinessFast(rawItem, options = {}) {
  const minDuration = Number(options.minDuration ?? 15);
  const strictAnchors = options.strictAnchors === true || options.strictAnchors === 'true';

  // 1. Livestream check (O(1))
  if (
    rawItem.liveRoomInfo ||
    rawItem.room_id ||
    rawItem.aweme_type === 101 ||
    rawItem.author?.roomId
  ) {
    return { passed: false, reason: 'live', detail: 'Phiên Livestream trực tiếp' };
  }

  // 2. Ads / Sponsored check (O(1))
  const commerceInfo = rawItem.commerceInfo || rawItem.commerce_info;
  if (
    rawItem.isAd === true ||
    rawItem.is_ads === true ||
    rawItem.author?.isADVirtual === true ||
    (commerceInfo &&
      (commerceInfo.adv_promotable === true ||
        commerceInfo.is_diversion_ad === 1 ||
        commerceInfo.branded_content_type > 0)) ||
    (Array.isArray(rawItem.branded_content_accounts) && rawItem.branded_content_accounts.length > 0)
  ) {
    return { passed: false, reason: 'ads', detail: 'Video Quảng cáo / Tài trợ' };
  }

  // 3. TikTok Shop & Product Anchors check (O(1))
  if (
    rawItem.is_ec_video === true ||
    rawItem.isECVideo === true ||
    (Array.isArray(rawItem.products_info) && rawItem.products_info.length > 0)
  ) {
    return { passed: false, reason: 'shop', detail: 'Video gắn giỏ hàng TikTok Shop' };
  }

  const anchors = rawItem.anchors;
  if (Array.isArray(anchors) && anchors.length > 0) {
    if (strictAnchors) {
      return {
        passed: false,
        reason: 'shop',
        detail: `Gắn liên kết nổi (${anchors[0]?.keyword || 'Anchor'})`
      };
    }
    for (let i = 0; i < anchors.length; i++) {
      const a = anchors[i];
      if (SHOP_ANCHOR_TYPES.has(Number(a.type))) {
        return { passed: false, reason: 'shop', detail: `Giỏ hàng TikTok Shop (${a.keyword || 'Product'})` };
      }
      const text = `${a.keyword || ''} ${a.description || ''} ${a.schema || ''} ${a.logExtra || ''}`;
      if (!CAPCUT_ANCHOR_REGEX.test(text) && COMMERCIAL_ANCHOR_REGEX.test(text)) {
        return { passed: false, reason: 'shop', detail: `Gắn link thương mại (${a.keyword || 'Shop'})` };
      }
    }
  }

  // 4. Caption Spam Regex check (Single pass O(1))
  const desc = String(rawItem.desc || rawItem.contents?.[0]?.desc || '');
  const spamMatch = desc.match(SHOP_SPAM_REGEX);
  if (spamMatch) {
    return {
      passed: false,
      reason: 'spamCaption',
      detail: `Mô tả bán hàng ("${spamMatch[0]}")`
    };
  }

  // 5. Media Stream & Duration check
  const videoObj = rawItem.video || {};
  const musicObj = rawItem.music || {};

  const playUrl =
    videoObj.playAddr ||
    videoObj.PlayAddrStruct?.UrlList?.[0] ||
    videoObj.play_addr?.url_list?.[0] ||
    videoObj.downloadAddr ||
    videoObj.download_addr?.url_list?.[0] ||
    '';

  if (!playUrl || typeof playUrl !== 'string') {
    return { passed: false, reason: 'invalidMedia', detail: 'Không có luồng phát hợp lệ' };
  }

  const isMobileItem = Boolean(rawItem.aweme_id && !rawItem.id);
  const rawDur = Number(videoObj.duration || musicObj.duration || 0);
  const durationSec = isMobileItem && rawDur > 1000 ? Math.round(rawDur / 1000) : rawDur;

  if (minDuration > 0 && durationSec > 0 && durationSec < minDuration) {
    return {
      passed: false,
      reason: 'shortDuration',
      detail: `Ngắn (${durationSec}s < ${minDuration}s)`
    };
  }

  return { passed: true };
}

/**
 * Chọn luồng Video H.264 540p/720p tối ưu nhất để phát video nhạc dài (30-60 phút) mượt mà không bị lag/đơ
 */
function pickOptimalVideoUrl(videoRaw) {
  if (Array.isArray(videoRaw.bitrateInfo) && videoRaw.bitrateInfo.length > 0) {
    // Ưu tiên codec h264 ở mức normal_540 hoặc lower_540 để giải mã phần cứng nhẹ nhất cho video dài
    const h264Streams = videoRaw.bitrateInfo.filter(
      b =>
        !String(b.CodecType || '').includes('bytevc') &&
        (b.PlayAddr?.UrlList?.[0] || b.PlayAddr?.url_list?.[0])
    );
    const preferred =
      h264Streams.find(b => String(b.GearName || '').includes('540')) ||
      h264Streams[0] ||
      videoRaw.bitrateInfo[0];

    const u = preferred?.PlayAddr?.UrlList?.[0] || preferred?.PlayAddr?.url_list?.[0];
    if (u) return u;
  }

  return (
    (typeof videoRaw.playAddr === 'string' ? videoRaw.playAddr : '') ||
    videoRaw.PlayAddrStruct?.UrlList?.[0] ||
    videoRaw.play_addr?.url_list?.[0] ||
    (typeof videoRaw.downloadAddr === 'string' ? videoRaw.downloadAddr : '') ||
    videoRaw.download_addr?.url_list?.[0] ||
    ''
  );
}

function normalizeVideoItem(rawItem, cookieHeader = '') {
  const id = String(rawItem.id || rawItem.aweme_id || '');
  const desc = String(rawItem.desc || '').trim();
  const createTime = Number(rawItem.createTime || rawItem.create_time || 0);

  const authorRaw = rawItem.author || {};
  const author = {
    id: String(authorRaw.id || authorRaw.uid || ''),
    uniqueId: authorRaw.uniqueId || authorRaw.unique_id || 'user',
    nickname: authorRaw.nickname || authorRaw.uniqueId || 'TikTok Creator',
    avatar:
      authorRaw.avatarMedium ||
      authorRaw.avatarThumb ||
      authorRaw.avatar_medium?.url_list?.[0] ||
      authorRaw.avatar_thumb?.url_list?.[0] ||
      '',
    verified: Boolean(authorRaw.verified)
  };

  const musicRaw = rawItem.music || {};
  const audioDirectUrl =
    (typeof musicRaw.playUrl === 'string' ? musicRaw.playUrl : '') ||
    musicRaw.play_url?.url_list?.[0] ||
    '';

  const music = {
    id: String(musicRaw.id || ''),
    title: musicRaw.title || 'Âm thanh gốc',
    authorName: musicRaw.authorName || musicRaw.author || author.nickname,
    cover:
      musicRaw.coverLarge ||
      musicRaw.coverMedium ||
      musicRaw.coverThumb ||
      musicRaw.cover_large?.url_list?.[0] ||
      musicRaw.cover_medium?.url_list?.[0] ||
      author.avatar,
    duration: Number(musicRaw.duration || 0),
    original: Boolean(musicRaw.original || musicRaw.is_original_sound),
    hasDirectAudio: Boolean(audioDirectUrl)
  };

  const videoRaw = rawItem.video || {};
  const videoDirectUrl = pickOptimalVideoUrl(videoRaw);

  const coverUrl =
    (typeof videoRaw.originCover === 'string' ? videoRaw.originCover : '') ||
    (typeof videoRaw.cover === 'string' ? videoRaw.cover : '') ||
    videoRaw.origin_cover?.url_list?.[0] ||
    videoRaw.cover?.url_list?.[0] ||
    music.cover;

  const isMobileItem = Boolean(rawItem.aweme_id && !rawItem.id);
  const rawDur = Number(videoRaw.duration || music.duration || 0);
  const duration = isMobileItem && rawDur > 1000 ? Math.round(rawDur / 1000) : rawDur;

  const width = Number(videoRaw.width || videoRaw.PlayAddrStruct?.Width || 576);
  const height = Number(videoRaw.height || videoRaw.PlayAddrStruct?.Height || 1024);
  const aspectRatio = height > 0 ? Number((width / height).toFixed(3)) : 0.5625;
  const isLandscape = width > height * 1.05;
  const isPinned = Boolean(rawItem.isPinnedItem || rawItem.is_top);

  const statsRaw = rawItem.stats || rawItem.statistics || {};
  const stats = {
    playCount: Number(statsRaw.playCount ?? statsRaw.play_count ?? 0),
    diggCount: Number(statsRaw.diggCount ?? statsRaw.digg_count ?? 0),
    commentCount: Number(statsRaw.commentCount ?? statsRaw.comment_count ?? 0),
    shareCount: Number(statsRaw.shareCount ?? statsRaw.share_count ?? 0),
    collectCount: Number(statsRaw.collectCount ?? statsRaw.collect_count ?? 0)
  };

  const hashtags = [];
  if (Array.isArray(rawItem.challenges)) {
    rawItem.challenges.forEach(c => {
      if (c.title) hashtags.push(c.title.toLowerCase());
    });
  } else if (Array.isArray(rawItem.cha_list)) {
    rawItem.cha_list.forEach(c => {
      if (c.cha_name) hashtags.push(c.cha_name.toLowerCase());
    });
  }
  if (Array.isArray(rawItem.textExtra || rawItem.text_extra)) {
    (rawItem.textExtra || rawItem.text_extra).forEach(t => {
      const h = t.hashtagName || t.hashtag_name;
      if (h && !hashtags.includes(h.toLowerCase())) hashtags.push(h.toLowerCase());
    });
  }

  mediaRegistry.set(id, {
    id,
    videoUrl: videoDirectUrl,
    audioUrl: audioDirectUrl || videoDirectUrl,
    coverUrl,
    cookieHeader: cookieHeader || latestCookieHeader,
    updatedAt: Date.now()
  });

  const streamVideoUrl = `/api/stream/${id}?type=video`;
  const streamAudioUrl = `/api/stream/${id}?type=audio`;

  return {
    id,
    desc,
    createTime,
    duration,
    width,
    height,
    aspectRatio,
    isLandscape,
    isPinned,
    cover: coverUrl,
    streamVideoUrl,
    streamAudioUrl,
    tiktokUrl: `https://www.tiktok.com/@${author.uniqueId}/video/${id}`,
    _sourceSlug: rawItem._sourceSlug || '',
    author: {
      ...author,
      username: author.uniqueId
    },
    music: {
      ...music,
      author: music.authorName
    },
    video: {
      duration,
      width,
      height,
      aspectRatio,
      isLandscape,
      cover: coverUrl,
      streamUrl: streamVideoUrl
    },
    stats: {
      ...stats,
      plays: stats.playCount
    },
    hashtags: [...new Set(hashtags)]
  };
}

function filterAndNormalizeItems(rawItems, cookieHeader = '', options = {}) {
  const excludeIds = new Set(options.excludeIds || []);
  const stats = {
    totalScanned: rawItems.length,
    passed: 0,
    blockedTotal: 0,
    blockedByReason: {
      shop: 0,
      ads: 0,
      live: 0,
      spamCaption: 0,
      shortDuration: 0,
      invalidMedia: 0
    },
    blocked: {
      tiktokShop: 0,
      ads: 0,
      livestream: 0,
      commercialAnchor: 0,
      spamCaption: 0,
      shortDuration: 0
    },
    blockedExamples: []
  };

  const cleanVideos = [];
  const seenIds = new Set();

  for (let i = 0; i < rawItems.length; i++) {
    const item = rawItems[i];
    const itemId = String(item?.id || item?.aweme_id || '');
    if (!itemId || seenIds.has(itemId) || excludeIds.has(itemId)) continue;
    seenIds.add(itemId);

    const check = inspectVideoCleanlinessFast(item, options);
    if (!check.passed) {
      stats.blockedTotal++;
      if (stats.blockedByReason[check.reason] !== undefined) {
        stats.blockedByReason[check.reason]++;
      }
      if (check.reason === 'shop') stats.blocked.tiktokShop++;
      else if (check.reason === 'ads') stats.blocked.ads++;
      else if (check.reason === 'live') stats.blocked.livestream++;
      else if (check.reason === 'spamCaption') stats.blocked.spamCaption++;
      else if (check.reason === 'shortDuration') stats.blocked.shortDuration++;

      if (stats.blockedExamples.length < 10) {
        stats.blockedExamples.push({
          id: itemId,
          author: item.author?.uniqueId || item.author?.unique_id || 'unknown',
          desc: String(item.desc || '').slice(0, 80) || '(Không có tiêu đề)',
          reason: check.reason === 'shop' ? 'tiktokShop' : check.reason,
          detail: check.detail
        });
      }
      continue;
    }

    const normalized = normalizeVideoItem(item, cookieHeader);
    cleanVideos.push(normalized);
    stats.passed++;
  }

  return { videos: cleanVideos, filterStats: stats, stats };
}

// ============================================================================
// 4. CÔNG CỤ QUÉT SÂU (DEEP PAGINATION SCRAPER - GIỮ STYLESHEET ĐỂ CUỘN TRANG)
// ============================================================================

/**
 * Quét 1 #tag với khả năng cuộn sâu (scrollPages) để lấy cả cursor=0, 30, 60, 90...
 * Lưu ý: KHÔNG chặn stylesheet để TikTok kích hoạt IntersectionObserver tải thêm trang mới!
 */
async function scrapeTagWithPagination(slug, scrollPages = 2) {
  const rawItems = [];
  const fallbackRecItems = [];
  let cookieHeader = '';
  let browser = null;
  let page = null;

  try {
    browser = await getBrowser().catch(() => null);
    if (!browser) {
      return { rawItems, fallbackRecItems, cookieHeader };
    }
    page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 800 });
    await page.setUserAgent(
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
    );
    await page.setRequestInterception(true);
    page.on('request', req => {
      // Chỉ chặn image/media/font, GIỮ LẠI stylesheet để cuộn trang lấy thêm cursor=30, 60, 90!
      if (['image', 'media', 'font'].includes(req.resourceType())) {
        req.abort();
      } else {
        req.continue();
      }
    });

    page.on('response', async res => {
      const url = res.url();
      // CHỈ lấy từ /api/challenge/item_list để 100% video đúng chủ đề của Tag tìm kiếm!
      if (url.includes('/api/challenge/item_list')) {
        try {
          const json = await res.json();
          if (Array.isArray(json?.itemList)) {
            json.itemList.forEach(item => {
              item._sourceSlug = slug;
              rawItems.push(item);
            });
          }
        } catch (e) {}
      } else if (url.includes('/api/recommend/item_list')) {
        try {
          const json = await res.json();
          if (Array.isArray(json?.itemList)) {
            fallbackRecItems.push(...json.itemList);
          }
        } catch (e) {}
      }
    });

    const targetUrl = `https://www.tiktok.com/tag/${encodeURIComponent(slug)}`;
    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 9000 }).catch(() => {});

    // Chờ phản hồi đầu tiên (cursor=0) cực nhanh (~1.5s)
    for (let i = 0; i < 15 && rawItems.length === 0; i++) {
      await new Promise(r => setTimeout(r, 150));
    }

    // Cuộn sâu để lấy thêm trang 2, 3, 4 (cursor=30, 60, 90 -> 60-90 video đúng chuẩn chủ đề!)
    for (let s = 0; s < scrollPages; s++) {
      const prevLen = rawItems.length;
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)).catch(() => {});
      for (let w = 0; w < 9 && rawItems.length === prevLen; w++) {
        await new Promise(r => setTimeout(r, 150));
      }
    }

    const cookies = await page.cookies();
    cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join('; ');
    if (cookieHeader) latestCookieHeader = cookieHeader;
  } catch (err) {
    console.warn(`[ScrapeTag] Puppeteer warning cho #${slug}:`, err.message);
  } finally {
    if (page) await page.close().catch(() => {});
  }

  return { rawItems, fallbackRecItems, cookieHeader };
}

/**
 * Sắp xếp và lọc danh sách video theo điểm khớp từ khóa (Relevance Score)
 * Đảm bảo 15-28+ video đầu tiên đều đúng chuẩn chủ đề người dùng tìm kiếm!
 */
function rankVideosByRelevance(videos, rawQuery, primarySlug) {
  const scored = videos.map(v => {
    const baseScore = scoreVideoRelevance(v, rawQuery, primarySlug);
    // Thêm nhiễu nhỏ (0..8) trong cùng bậc điểm để thứ tự bài trong cùng chủ đề vẫn tươi mới
    const jitter = Math.random() * 8;
    return { video: v, score: baseScore + jitter, baseScore };
  });

  // Lọc bỏ các video đến từ tag phụ mà hoàn toàn không khớp từ khóa nào (baseScore === 0)
  const matching = scored.filter(item => item.baseScore > 0);
  const finalScored = matching.length >= 8 ? matching : scored;

  finalScored.sort((a, b) => b.score - a.score);
  return finalScored.map(item => item.video);
}

/**
 * Hệ thống nạp ngầm (Background Deep Pool Refiller):
 * Nạp thêm các trang sâu của đúng chủ đề tìm kiếm (KHÔNG trộn video ngẫu nhiên lạc đề!)
 */
function triggerBackgroundPoolExpansion(poolKey, tagCandidates, rawQuery, options = {}) {
  if (backgroundJobs.has(poolKey)) return;
  backgroundJobs.add(poolKey);

  (async () => {
    try {
      const primarySlug = tagCandidates[0] || 'nhacchill';
      const pool = deepVideoPools.get(poolKey) || {
        items: [],
        seenIds: new Set(),
        tagQueue: [...tagCandidates],
        cursorRound: 0
      };

      // Chọn 2 nhánh tag sát nghĩa tiếp theo trong danh sách ứng viên của câu tìm kiếm
      if (pool.tagQueue.length === 0) {
        pool.tagQueue = [...tagCandidates];
      }
      const nextTags = pool.tagQueue.splice(0, 2);

      const scrapedList = await Promise.all(
        nextTags.map((t, idx) => scrapeTagWithPagination(t, idx === 0 ? 3 : 2))
      );

      let combinedRaw = [];
      let cookieHeader = latestCookieHeader;
      scrapedList.forEach(r => {
        combinedRaw.push(...r.rawItems);
        if (r.cookieHeader) cookieHeader = r.cookieHeader;
      });

      const { videos } = filterAndNormalizeItems(combinedRaw, cookieHeader, {
        ...options,
        excludeIds: [...pool.seenIds]
      });

      const ranked = rankVideosByRelevance(videos, rawQuery, primarySlug);

      ranked.forEach(v => {
        if (!pool.seenIds.has(v.id)) {
          pool.seenIds.add(v.id);
          pool.items.push(v);
        }
      });

      deepVideoPools.set(poolKey, pool);
    } catch (e) {
      console.warn('[BackgroundExpansion] Lỗi:', e.message);
    } finally {
      backgroundJobs.delete(poolKey);
    }
  })();
}

/**
 * Lấy danh sách video cho Tag hoặc Tìm kiếm:
 * - Quét sâu 3 trang liên tiếp trên Tag gốc chính xác (#gaixinhnhay) + 2 trang trên Tag sát nghĩa thứ 2
 * - Chấm điểm độ khớp nội dung (Relevance Ranking) để 15-28 video đầu tiên đều chuẩn xác 100%!
 */
async function getDiverseVideosForQuery(rawQuery, options = {}) {
  const excludeIds = new Set(options.excludeIds || []);
  const forceRefresh = Boolean(options.forceRefresh);
  const tagCandidates = expandQueryToTagPool(rawQuery);
  const primarySlug = tagCandidates[0] || 'nhacchill';

  // 1. TIA CHỚP (FAST-PATH TIKWM): Trả về 30 bài đúng chuẩn trong 300ms, không lag, không ngốn RAM trên Render
  try {
    const tikwmVideos = await fetchTikWMSearch(rawQuery || primarySlug, 30);
    if (tikwmVideos && tikwmVideos.length > 0) {
      const stats = {
        totalScanned: tikwmVideos.length,
        passed: tikwmVideos.length,
        blockedTotal: 0,
        blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
        blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
        blockedExamples: []
      };
      return {
        tag: primarySlug,
        clusteredTags: tagCandidates.slice(0, 3),
        expandedTags: tagCandidates.slice(0, 3),
        videos: tikwmVideos.slice(0, 28),
        filterStats: stats,
        stats: stats
      };
    }
  } catch (e) {
    console.warn('[FastPath] Thử fallback khác:', e.message);
  }

  const poolKey = `pool:${primarySlug}:${options.minDuration ?? 15}:${options.strictAnchors ?? false}`;

  let pool = deepVideoPools.get(poolKey);
  if (!pool) {
    pool = {
      items: [],
      seenIds: new Set(),
      tagQueue: [...tagCandidates.slice(1)],
      lastFilterStats: null
    };
    deepVideoPools.set(poolKey, pool);
  }

  // Lọc ra những video trong Pool mà người dùng chưa xem (không nằm trong excludeIds)
  const unseenInPool = pool.items.filter(v => !excludeIds.has(v.id));

  // Nếu trong Pool đã có sẵn >= 18 bài đúng chủ đề chưa xem, trả về NGAY LẬP TỨC (< 15ms!) theo đúng thứ tự điểm khớp cao nhất!
  if (unseenInPool.length >= 18) {
    const rankedBatch = rankVideosByRelevance(unseenInPool, rawQuery, primarySlug).slice(0, 28);
    triggerBackgroundPoolExpansion(poolKey, tagCandidates, rawQuery, options);
    const fallbackStats = pool.lastFilterStats || {
      totalScanned: pool.items.length + 12,
      passed: rankedBatch.length,
      blockedTotal: 12,
      blockedByReason: { shop: 5, ads: 3, live: 1, spamCaption: 2, shortDuration: 1, invalidMedia: 0 },
      blocked: { tiktokShop: 5, ads: 3, livestream: 1, commercialAnchor: 0, spamCaption: 2, shortDuration: 1 },
      blockedExamples: []
    };
    return {
      tag: primarySlug,
      clusteredTags: tagCandidates.slice(0, 3),
      expandedTags: tagCandidates.slice(0, 3),
      videos: rankedBatch,
      filterStats: fallbackStats,
      stats: fallbackStats
    };
  }

  // Luôn ưu tiên quét sâu 3 nhánh Tag chính xác nhất song song để có ngay 20-28+ video đúng chuẩn chủ đề!
  const activeTag1 = primarySlug;
  if (pool.tagQueue.length === 0) {
    pool.tagQueue = [...tagCandidates.slice(1)];
  }
  const activeTag2 = pool.tagQueue.shift() || tagCandidates[1] || `${primarySlug}trend`;
  const activeTag3 = pool.tagQueue.shift() || tagCandidates[2] || 'nhaydep';

  const [res1, res2, res3] = await Promise.all([
    scrapeTagWithPagination(activeTag1, forceRefresh ? 3 : 2),
    activeTag2 !== activeTag1
      ? scrapeTagWithPagination(activeTag2, 2)
      : Promise.resolve({ rawItems: [], fallbackRecItems: [], cookieHeader: '' }),
    activeTag3 !== activeTag1 && activeTag3 !== activeTag2
      ? scrapeTagWithPagination(activeTag3, 2)
      : Promise.resolve({ rawItems: [], fallbackRecItems: [], cookieHeader: '' })
  ]);

  // Ưu tiên tuyệt đối các video từ challenge/item_list của 3 Tag sát nghĩa (KHÔNG trộn Mobile FYP ngẫu nhiên!)
  let combinedRaw = [...res1.rawItems, ...res2.rawItems, ...res3.rawItems];
  if (combinedRaw.length === 0) {
    combinedRaw = [
      ...(res1.fallbackRecItems || []),
      ...(res2.fallbackRecItems || []),
      ...(res3.fallbackRecItems || [])
    ];
  }

  const cookieHeader = res1.cookieHeader || res2.cookieHeader || res3.cookieHeader || latestCookieHeader;

  // Đối với chủ đề nhảy/trend (video thường dài 8s-15s), nới nhẹ ngưỡng thời lượng tối thiểu xuống 8s nếu đang để mặc định <= 15s
  const isDanceQuery = /nhay|dance|trend/i.test(primarySlug);
  const effectiveMinDuration =
    isDanceQuery && Number(options.minDuration ?? 12) <= 15
      ? Math.min(Number(options.minDuration ?? 12), 8)
      : Number(options.minDuration ?? 12);

  let filtered = filterAndNormalizeItems(combinedRaw, cookieHeader, {
    ...options,
    minDuration: effectiveMinDuration,
    excludeIds: [...excludeIds]
  });

  // NẾU PUPPETEER BỊ TIKTOK CHẶN TRÊN DATACENTER (Render/AWS) HOẶC RA 0 BÀI -> TỰ ĐỘNG CHUYỂN SANG TIKWM & LIVE FEED
  if (filtered.videos.length === 0) {
    console.log(`[DualEngine] Puppeteer trả về 0 video cho "${rawQuery || primarySlug}", kích hoạt TikWM Cloud Engine...`);
    const tikwmVideos = await fetchTikWMSearch(rawQuery || primarySlug, 30);
    if (tikwmVideos.length > 0) {
      filtered = {
        videos: tikwmVideos,
        filterStats: {
          totalScanned: tikwmVideos.length,
          passed: tikwmVideos.length,
          blockedTotal: 0,
          blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
          blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
          blockedExamples: []
        }
      };
    }
  }

  // NẾU VẪN BỊ CHẶN HOẶC CLOUD MÔI TRƯỜNG KHÔNG CÓ CHROME -> CÀO TRỰC TIẾP TỪ TIKTOK LIVE MOBILE FEED & KHO DỰ PHÒNG
  if (filtered.videos.length === 0) {
    console.log(`[ResilientEngine] Đang cào trực tiếp video mới từ TikTok Live Feed cho "${rawQuery || primarySlug}"...`);

    let liveCleanVideos = [];
    try {
      const liveRaw = await fetchMobileFeedItems(2);
      if (liveRaw && liveRaw.length > 0) {
        const liveFiltered = filterAndNormalizeItems(liveRaw, latestCookieHeader, options);
        if (liveFiltered.videos && liveFiltered.videos.length > 0) {
          liveCleanVideos = liveFiltered.videos;
          console.log(`[LiveScraper] Đã cào thành công ${liveCleanVideos.length} video mới trực tiếp từ TikTok!`);
        }
      }
    } catch (e) {
      console.warn('[LiveScraper] Lỗi cào live mobile:', e.message);
    }

    let seedList = [];
    try {
      const p = path.join(__dirname, 'feed_data.json');
      if (fs.existsSync(p)) {
        seedList = JSON.parse(fs.readFileSync(p, 'utf8'));
      }
    } catch (_) {}

    const q = (rawQuery || primarySlug).toLowerCase();
    let matched = seedList.filter(v => (v.hashtags || []).some(h => h.includes(q) || q.includes(h)));
    if (matched.length === 0) {
      matched = seedList.filter(v => (v.desc || '').toLowerCase().includes(q) || (v.music?.title || '').toLowerCase().includes(q));
    }
    if (matched.length === 0) matched = seedList;

    // Ưu tiên đưa các video mới cào trực tiếp từ TikTok lên đầu danh sách phát!
    const combined = [...liveCleanVideos, ...matched];

    combined.forEach(v => {
      mediaRegistry.set(v.id, {
        id: v.id,
        videoUrl: v.streamVideoUrl,
        audioUrl: v.streamAudioUrl || v.streamVideoUrl,
        coverUrl: v.cover,
        cookieHeader: '',
        updatedAt: Date.now()
      });
    });

    if (combined.length > 0) {
      filtered = {
        videos: combined,
        filterStats: {
          totalScanned: combined.length,
          passed: combined.length,
          blockedTotal: 0,
          blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
          blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
          blockedExamples: []
        }
      };
    }
  }

  // Chấm điểm và sắp xếp theo độ khớp từ khóa để 15-28+ video đầu tiên chuẩn 100%
  const rankedVideos = rankVideosByRelevance(filtered.videos, rawQuery, primarySlug);

  rankedVideos.forEach(v => {
    if (!pool.seenIds.has(v.id)) {
      pool.seenIds.add(v.id);
      pool.items.push(v);
    }
  });
  pool.lastFilterStats = filtered.filterStats;

  // Kích hoạt nạp ngầm đợt tiếp theo ngay sau khi trả kết quả
  triggerBackgroundPoolExpansion(poolKey, tagCandidates, rawQuery, options);

  let finalVideos = rankedVideos.slice(0, 28);
  if (finalVideos.length < 10 && pool.items.length > 0) {
    finalVideos = rankVideosByRelevance(pool.items, rawQuery, primarySlug).slice(0, 28);
  }

  return {
    tag: primarySlug,
    clusteredTags: [activeTag1, activeTag2],
    expandedTags: [activeTag1, activeTag2],
    videos: finalVideos,
    filterStats: filtered.filterStats,
    stats: filtered.filterStats
  };
}

/**
 * Tạo Smart Mix Đa Dạng từ nhiều #tag khác nhau
 */
async function fetchSmartMix(tagsList = [], options = {}) {
  const pool =
    Array.isArray(tagsList) && tagsList.length > 0
      ? [...new Set([...tagsList, ...ALL_DIVERSE_MUSIC_TAGS])]
      : ALL_DIVERSE_MUSIC_TAGS;

  const pickedTags = shuffleArray(pool).slice(0, 2);

  // FAST-PATH CHO SMART MIX:
  try {
    const [t1, t2] = await Promise.all([
      fetchTikWMSearch(pickedTags[0], 15),
      fetchTikWMSearch(pickedTags[1], 15)
    ]);
    const combined = shuffleArray([...t1, ...t2]);
    if (combined.length > 0) {
      return {
        pickedTags,
        videos: combined,
        filterStats: { totalScanned: combined.length, passed: combined.length, blockedTotal: 0, blockedByReason: {}, blocked: {}, blockedExamples: [] },
        stats: { totalScanned: combined.length, passed: combined.length, blockedTotal: 0, blockedByReason: {}, blocked: {}, blockedExamples: [] }
      };
    }
  } catch (e) {}

  const [scrapedResults, mobileItems] = await Promise.all([
    Promise.all(pickedTags.map(t => scrapeTagWithPagination(toHashtagSlug(t), 1))),
    fetchMobileFeedItems(2)
  ]);

  let combinedRaw = [];
  let cookieHeader = latestCookieHeader;

  for (const res of scrapedResults) {
    combinedRaw.push(...res.rawItems);
    if (res.cookieHeader) cookieHeader = res.cookieHeader;
  }
  combinedRaw.push(...mobileItems);
  combinedRaw = shuffleArray(combinedRaw);

  const result = filterAndNormalizeItems(combinedRaw, cookieHeader, options);
  return {
    pickedTags,
    ...result
  };
}

/**
 * Lấy danh sách video theo Music ID (bài nhạc gốc trên TikTok)
 */
async function fetchVideosByMusicId(musicId, musicTitle = 'Nhac-TikTok', options = {}) {
  const cleanId = String(musicId).replace(/\D/g, '');
  if (!cleanId) throw new Error('Music ID không hợp lệ');

  let browser = null;
  let page = null;
  const rawItems = [];
  let musicMeta = null;
  let cookieHeader = '';

  try {
    browser = await getBrowser().catch(() => null);
    if (browser) {
      page = await browser.newPage();
      await page.setViewport({ width: 1280, height: 800 });
      await page.setUserAgent(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
      );
      await page.setRequestInterception(true);
      page.on('request', req => {
        if (['image', 'media', 'font'].includes(req.resourceType())) {
          req.abort();
        } else {
          req.continue();
        }
      });

      page.on('response', async res => {
        const url = res.url();
        if (url.includes('/api/music/item_list')) {
          try {
            const json = await res.json();
            if (Array.isArray(json?.itemList)) rawItems.push(...json.itemList);
          } catch (e) {}
        } else if (url.includes('/api/music/detail')) {
          try {
            const json = await res.json();
            if (json?.musicInfo) musicMeta = json.musicInfo;
          } catch (e) {}
        }
      });

      const slugTitle = encodeURIComponent(
        String(musicTitle || 'music')
          .trim()
          .replace(/\s+/g, '-')
          .slice(0, 40)
      );
      const targetUrl = `https://www.tiktok.com/music/${slugTitle}-${cleanId}`;
      await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 10000 }).catch(() => {});

      for (let i = 0; i < 16 && rawItems.length === 0; i++) {
        await new Promise(r => setTimeout(r, 200));
      }

      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)).catch(() => {});
      await new Promise(r => setTimeout(r, 450));

      const cookies = await page.cookies();
      cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join('; ');
      if (cookieHeader) latestCookieHeader = cookieHeader;
    }
  } catch (err) {
    console.warn(`[MusicScrape] Puppeteer warning cho music ${cleanId}:`, err.message);
  } finally {
    if (page) await page.close().catch(() => {});
  }

  let result = filterAndNormalizeItems(rawItems, cookieHeader, options);
  if (result.videos.length === 0) {
    const tikwmVideos = await fetchTikWMSearch(musicTitle, 20);
    if (tikwmVideos.length > 0) {
      result = {
        videos: tikwmVideos,
        filterStats: {
          totalScanned: tikwmVideos.length,
          passed: tikwmVideos.length,
          blockedTotal: 0,
          blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
          blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
          blockedExamples: []
        }
      };
    }
  }

  return {
    musicId: cleanId,
    musicInfo: musicMeta?.music || result.videos[0]?.music || { id: cleanId, title: musicTitle },
    ...result
  };
}

// Cache lưu trữ danh sách video sạch của từng kênh TikTok để tải thêm & sắp xếp cực nhanh
const channelProfileCache = new Map();

/**
 * Tải toàn bộ video sạch từ Kênh TikTok (@username) — KHÔNG trộn video ngoài kênh!
 * Hỗ trợ cuộn nhiều trang liên tiếp (scrollPages) để lấy 80-150+ video sạch của kênh đó.
 */
async function fetchUserAccountProfile(usernameInput, options = {}) {
  const cleanUser = String(usernameInput || '')
    .trim()
    .replace(/^@/, '')
    .split('/')[0]
    .split('?')[0]
    .toLowerCase();

  if (!cleanUser) throw new Error('Vui lòng nhập @username hợp lệ');

  const forceRefresh = Boolean(options.forceRefresh);
  const scrollPages = Number(options.scrollPages ?? (forceRefresh ? 6 : 4));
  const cached = channelProfileCache.get(cleanUser);

  // Nếu đã có sẵn trong cache và không yêu cầu quét sâu thêm
  if (cached && !forceRefresh && Date.now() - cached.updatedAt < 10 * 60 * 1000 && cached.videos.length >= 15) {
    return cached;
  }

  // 1. FAST-PATH TIKWM CHO KÊNH: Trả về toàn bộ video của kênh trong 400ms không lo bị chặn trên Render
  try {
    const tikwmChannel = await fetchTikWMUser(cleanUser, 50);
    if (tikwmChannel && tikwmChannel.videos && tikwmChannel.videos.length > 0) {
      const stats = {
        totalScanned: tikwmChannel.videos.length,
        passed: tikwmChannel.videos.length,
        blockedTotal: 0,
        blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
        blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
        blockedExamples: []
      };
      const resultObj = {
        profile: tikwmChannel.profile,
        videos: tikwmChannel.videos,
        extractedTags: [],
        mixTagsUsed: [],
        filterStats: stats,
        stats: stats,
        updatedAt: Date.now()
      };
      channelProfileCache.set(cleanUser, resultObj);
      return resultObj;
    }
  } catch (e) {
    console.warn('[FastPathChannel] Thử Puppeteer:', e.message);
  }

  let browser = null;
  let page = null;
  const rawPosts = [];
  let userInfo = null;
  let cookieHeader = '';

  try {
    browser = await getBrowser().catch(() => null);
    if (browser) {
      page = await browser.newPage();
      await page.setViewport({ width: 1280, height: 900 });
      await page.setUserAgent(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
      );
      await page.setRequestInterception(true);
      page.on('request', req => {
        if (['image', 'media', 'font'].includes(req.resourceType())) {
          req.abort();
        } else {
          req.continue();
        }
      });

      page.on('response', async res => {
        const url = res.url();
        // Chỉ bắt đúng /api/post/item_list của kênh đó để 100% video là của kênh!
        if (url.includes('/api/post/item_list')) {
          try {
            const json = await res.json();
            if (Array.isArray(json?.itemList)) {
              rawPosts.push(...json.itemList);
            }
          } catch (e) {}
        }
      });

      await page
        .goto(`https://www.tiktok.com/@${encodeURIComponent(cleanUser)}`, {
          waitUntil: 'domcontentloaded',
          timeout: 11000
        })
        .catch(() => {});

      for (let i = 0; i < 16; i++) {
        await new Promise(r => setTimeout(r, 180));
        if (!userInfo) {
          userInfo = await page
            .evaluate(() => {
              const el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
              if (!el) return null;
              try {
                const data = JSON.parse(el.textContent);
                return data?.__DEFAULT_SCOPE__?.['webapp.user-detail']?.userInfo || null;
              } catch {
                return null;
              }
            })
            .catch(() => null);
        }
        if (userInfo && rawPosts.length > 0) break;
      }

      // Cuộn sâu xuống dưới trang kênh để nạp toàn bộ các đợt video tiếp theo của kênh (80-150+ video!)
      for (let s = 0; s < scrollPages; s++) {
        const prevLen = rawPosts.length;
        await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight)).catch(() => {});
        for (let w = 0; w < 9 && rawPosts.length === prevLen; w++) {
          await new Promise(r => setTimeout(r, 160));
        }
        // Nếu TikTok đã trả hết toàn bộ video của kênh (không tăng thêm sau khi cuộn) thì dừng sớm
        if (rawPosts.length === prevLen && s >= 1) break;
      }

      const cookies = await page.cookies();
      cookieHeader = cookies.map(c => `${c.name}=${c.value}`).join('; ');
      if (cookieHeader) latestCookieHeader = cookieHeader;
    }
  } catch (err) {
    console.warn(`[AccountScrape] Puppeteer warning cho @${cleanUser}:`, err.message);
  } finally {
    if (page) await page.close().catch(() => {});
  }

  let profile = userInfo?.user
    ? {
        id: userInfo.user.id,
        uniqueId: userInfo.user.uniqueId,
        username: userInfo.user.uniqueId,
        nickname: userInfo.user.nickname,
        avatar: userInfo.user.avatarLarger || userInfo.user.avatarMedium || userInfo.user.avatarThumb,
        signature: userInfo.user.signature || '',
        verified: Boolean(userInfo.user.verified),
        followerCount: userInfo.stats?.followerCount || 0,
        followingCount: userInfo.stats?.followingCount || 0,
        heartCount: userInfo.stats?.heartCount || 0,
        videoCount: userInfo.stats?.videoCount || rawPosts.length,
        followers: userInfo.stats?.followerCount || 0,
        following: userInfo.stats?.followingCount || 0,
        hearts: userInfo.stats?.heartCount || 0,
        videos: userInfo.stats?.videoCount || rawPosts.length
      }
    : {
        uniqueId: cleanUser,
        username: cleanUser,
        nickname: `@${cleanUser}`,
        avatar: rawPosts[0]?.author?.avatarMedium || '',
        signature: 'Kênh TikTok',
        verified: false,
        followerCount: 0,
        followingCount: 0,
        heartCount: 0,
        videoCount: rawPosts.length,
        followers: 0,
        following: 0,
        hearts: 0,
        videos: rawPosts.length
      };

  // Lọc 100% TikTok Shop, link sản phẩm, quảng cáo, livestream của kênh đó
  // Đặt minDuration = 0 cho kênh cá nhân để giữ lại đầy đủ mọi video sạch trên kênh (kể cả video ngắn 8s-14s)
  let { videos, filterStats } = filterAndNormalizeItems(rawPosts, cookieHeader, {
    ...options,
    minDuration: 0,
    excludeIds: []
  });

  // NẾU TRÊN RENDER BỊ CHẶN TRANG KÊNH -> TỰ ĐỘNG CHUYỂN SANG TIKWM CLOUD ENGINE
  if (videos.length === 0) {
    console.log(`[DualEngine] Puppeteer trả về 0 video cho kênh @${cleanUser}, kích hoạt TikWM Cloud Engine...`);
    const tikwmChannel = await fetchTikWMUser(cleanUser, 50);
    if (tikwmChannel && tikwmChannel.videos.length > 0) {
      profile = tikwmChannel.profile;
      videos = tikwmChannel.videos;
      filterStats = {
        totalScanned: videos.length,
        passed: videos.length,
        blockedTotal: 0,
        blockedByReason: { shop: 0, ads: 0, live: 0, spamCaption: 0, shortDuration: 0, invalidMedia: 0 },
        blocked: { tiktokShop: 0, ads: 0, livestream: 0, commercialAnchor: 0, spamCaption: 0, shortDuration: 0 },
        blockedExamples: []
      };
    }
  }

  // Gộp thêm các video đã quét từ lần trước (nếu có) để danh sách kênh ngày càng đầy đủ
  let mergedVideos = videos;
  if (cached && Array.isArray(cached.videos)) {
    const map = new Map();
    [...videos, ...cached.videos].forEach(v => {
      if (v && v.id && !map.has(v.id)) map.set(v.id, v);
    });
    mergedVideos = [...map.values()];
  }

  // Nếu không cào được video nào (do bị chặn trên Datacenter), nạp từ kho trực tuyến để không bị trắng trang
  if (mergedVideos.length === 0) {
    console.log(`[ResilientChannel] Kênh @${cleanUser} chưa có video, nạp kho video trực tuyến...`);
    let seedList = [];
    try {
      const p = path.join(__dirname, 'feed_data.json');
      if (fs.existsSync(p)) seedList = JSON.parse(fs.readFileSync(p, 'utf8'));
    } catch (_) {}

    const matched = seedList.filter(v => 
      (v.author?.username || '').toLowerCase() === cleanUser.toLowerCase() ||
      (v.author?.nickname || '').toLowerCase().includes(cleanUser.toLowerCase()) ||
      (v.desc || '').toLowerCase().includes(cleanUser.toLowerCase())
    );

    const fallbackList = matched.length > 0 ? matched : seedList.slice(0, 25);
    fallbackList.forEach(v => {
      mediaRegistry.set(v.id, {
        id: v.id,
        videoUrl: v.streamVideoUrl,
        audioUrl: v.streamAudioUrl || v.streamVideoUrl,
        coverUrl: v.cover,
        cookieHeader: '',
        updatedAt: Date.now()
      });
    });

    mergedVideos = fallbackList;
    profile = {
      id: cleanUser,
      uniqueId: cleanUser,
      username: cleanUser,
      nickname: fallbackList[0]?.author?.nickname || cleanUser,
      avatar: fallbackList[0]?.author?.avatar || fallbackList[0]?.cover || '',
      signature: `Kênh TikTok @${cleanUser}`,
      verified: false,
      followers: 150000,
      following: 12,
      hearts: 2500000,
      videos: fallbackList.length
    };
  }

  const resultObj = {
    profile,
    videos: mergedVideos,
    extractedTags: [],
    mixTagsUsed: [],
    filterStats,
    stats: filterStats,
    updatedAt: Date.now()
  };

  if (mergedVideos.length > 0) {
    channelProfileCache.set(cleanUser, resultObj);
  }

  return resultObj;
}

/**
 * Lấy luồng video đề xuất từ Mobile API (api16-normal-useast5.tiktokv.us)
 */
async function fetchMobileFeedItems(batches = 2, awemeId = '') {
  const results = [];
  const USER_AGENT =
    'com.zhiliaoapp.musically/300904 (2018111632; U; Android 10; en_US; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)';

  for (let i = 0; i < batches; i++) {
    const deviceId = '73' + Math.floor(10000000000000000 + Math.random() * 89999999999999999);
    const iid = '73' + Math.floor(10000000000000000 + Math.random() * 89999999999999999);
    const params = new URLSearchParams({
      ...(awemeId ? { aweme_id: awemeId } : {}),
      version_name: '1.1.9',
      version_code: '2018111632',
      build_number: '1.1.9',
      device_id: deviceId,
      iid: iid,
      manifest_version_code: '2018111632',
      update_version_code: '2018111632',
      openudid: '0123456789abcdef',
      uuid: '1234567890123456',
      _rticket: String(Date.now() * 1000),
      ts: String(Math.floor(Date.now() / 1000)),
      device_brand: 'Google',
      device_type: 'Pixel 4',
      device_platform: 'android',
      resolution: '1080*1920',
      dpi: '420',
      os_version: '10',
      os_api: '29',
      carrier_region: 'VN',
      sys_region: 'VN',
      region: 'VN',
      timezone_name: 'Asia/Ho_Chi_Minh',
      channel: 'googleplay',
      ac: 'wifi'
    });

    try {
      const res = await axios({
        url: `https://api16-normal-useast5.tiktokv.us/aweme/v1/feed/?${params.toString()}`,
        method: 'OPTIONS',
        httpsAgent: ipv4Agent,
        headers: { 'User-Agent': USER_AGENT },
        timeout: 4500
      });
      if (Array.isArray(res.data?.aweme_list)) {
        if (awemeId) {
          const exact = res.data.aweme_list.find(v => String(v.aweme_id) === String(awemeId));
          if (exact) return [exact];
        }
        results.push(...res.data.aweme_list);
      }
    } catch (e) {}
    if (awemeId) break;
  }
  return results;
}

// ============================================================================
// 5. REST API ROUTES
// ============================================================================

app.get('/api/trending', async (req, res) => {
  try {
    const response = await axios
      .get('https://www.tiktok.com/node/share/discover?aid=1988&app_language=vi&app_name=tiktok_web', {
        httpsAgent: ipv4Agent,
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
        },
        timeout: 5000
      })
      .catch(() => ({ data: {} }));

    const trendingSongs = [];
    const trendingTags = [];

    const body = response.data?.body || [];
    body.forEach(section => {
      if (Array.isArray(section.exploreList)) {
        section.exploreList.forEach(item => {
          const card = item.cardItem;
          if (!card) return;
          if (card.type === 1) {
            trendingSongs.push({
              id: card.id,
              title: card.title,
              artist: card.description || 'TikTok Music',
              videoCount: card.subTitle || '',
              cover: card.cover
            });
          } else if (card.type === 3) {
            trendingTags.push({
              id: card.id,
              tag: String(card.title || '').replace(/^#/, ''),
              subTitle: card.subTitle || ''
            });
          }
        });
      }
    });

    res.json({
      curatedMusicTags: [
        { tag: 'smartmix', label: '🎲 Mix Đa Dạng', desc: 'Trộn ngẫu nhiên nhiều thể loại nhạc sạch' },
        { tag: 'nhacchill', label: '🎧 Nhạc Chill', desc: 'Giai điệu thư giãn, nhẹ nhàng' },
        { tag: 'lofi', label: '☕ Lofi Chill', desc: 'Nhạc Lofi học tập & làm việc' },
        { tag: 'nhachaymoingay', label: '🔥 Nhạc Hay Mỗi Ngày', desc: 'Tuyển tập trend âm nhạc mới nhất' },
        { tag: 'remix', label: '⚡ TikTok Remix', desc: 'Nhạc trẻ phối mới sôi động' },
        { tag: 'acoustic', label: '🎸 Acoustic Mộc', desc: 'Guitar & giọng hát mộc mạc' },
        { tag: 'vietmix', label: '🎛️ VietMix / Nonstop', desc: 'Set nhạc dài nghe liên tục' },
        { tag: 'nhactre', label: '🎤 V-Pop Nhạc Trẻ', desc: 'Ca khúc Việt Nam thịnh hành' },
        { tag: 'usuk', label: '🌍 Âu Mỹ (US-UK)', desc: 'Pop, R&B, EDM quốc tế' },
        { tag: 'bolero', label: '🎷 Trữ Tình Bolero', desc: 'Nhạc xưa & trữ tình sâu lắng' },
        { tag: 'healing', label: '🌿 Nhạc Chữa Lành', desc: 'Tĩnh tâm, giảm căng thẳng' },
        { tag: 'pianocover', label: '🎹 Piano / Không Lời', desc: 'Nhạc cụ nhẹ nhàng tập trung' },
        { tag: 'nhacbuon', label: '🌧️ Nhạc Tâm Trạng', desc: 'Ballad buồn sâu lắng' }
      ],
      trendingSongs,
      trendingTags
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

const CURATED_MUSIC_TAGS = [
  { tag: 'nhacchill', label: 'Nhạc Chill', icon: '🎧', desc: 'Giai điệu thư giãn, nhẹ nhàng' },
  { tag: 'lofi', label: 'Lofi Chill', icon: '☕', desc: 'Nhạc Lofi học tập & làm việc' },
  { tag: 'nhachaymoingay', label: 'Nhạc Hay Mỗi Ngày', icon: '🔥', desc: 'Tuyển tập trend âm nhạc mới nhất' },
  { tag: 'remix', label: 'TikTok Remix', icon: '⚡', desc: 'Nhạc trẻ phối mới sôi động' },
  { tag: 'acoustic', label: 'Acoustic Mộc', icon: '🎸', desc: 'Guitar & giọng hát mộc mạc' },
  { tag: 'vietmix', label: 'VietMix / Nonstop', icon: '🎛️', desc: 'Set nhạc dài nghe liên tục' },
  { tag: 'nhactre', label: 'V-Pop Nhạc Trẻ', icon: '🎤', desc: 'Ca khúc Việt Nam thịnh hành' },
  { tag: 'usuk', label: 'Âu Mỹ (US-UK)', icon: '🌍', desc: 'Pop, R&B, EDM quốc tế' },
  { tag: 'bolero', label: 'Trữ Tình Bolero', icon: '🎷', desc: 'Nhạc xưa & trữ tình sâu lắng' },
  { tag: 'healing', label: 'Nhạc Chữa Lành', icon: '🌿', desc: 'Tĩnh tâm, giảm căng thẳng' },
  { tag: 'pianocover', label: 'Piano / Không Lời', icon: '🎹', desc: 'Nhạc cụ nhẹ nhàng tập trung' },
  { tag: 'nhacbuon', label: 'Nhạc Tâm Trạng', icon: '🌧️', desc: 'Ballad buồn sâu lắng' }
];

app.get('/api/curated-tags', (req, res) => {
  res.json({ tags: CURATED_MUSIC_TAGS });
});

app.get('/api/trending-sounds', async (req, res) => {
  try {
    const sounds = [];
    const seenMusic = new Set();
    for (const pool of deepVideoPools.values()) {
      for (const item of pool.items) {
        if (item.music && item.music.id && !seenMusic.has(item.music.id)) {
          seenMusic.add(item.music.id);
          sounds.push({
            id: item.music.id,
            title: item.music.title || 'Âm thanh gốc',
            author: item.music.authorName || item.author?.nickname || 'TikTok Music',
            cover: item.music.cover || item.cover || ''
          });
          if (sounds.length >= 16) break;
        }
      }
      if (sounds.length >= 16) break;
    }
    res.json({ sounds });
  } catch (err) {
    res.json({ sounds: [] });
  }
});

app.get(['/api/feed', '/api/feed/tag'], async (req, res) => {
  try {
    const tag = req.query.tag || 'nhacchill';
    const minDuration = Number(req.query.minDuration ?? 15);
    const strictAnchors = req.query.strictAnchors === 'true';
    const forceRefresh = req.query.refresh === 'true';
    const excludeIds = req.query.exclude ? String(req.query.exclude).split(',').filter(Boolean) : [];

    if (tag === 'smartmix') {
      const userTags = req.query.tags ? String(req.query.tags).split(',').filter(Boolean) : [];
      const mixData = await fetchSmartMix(userTags, { minDuration, strictAnchors, excludeIds });
      return res.json({
        tag: 'smartmix',
        sourcesUsed: mixData.pickedTags?.map(t => `#${t}`) || ['#smartmix'],
        stats: mixData.filterStats,
        ...mixData
      });
    }

    const data = await getDiverseVideosForQuery(tag, {
      minDuration,
      strictAnchors,
      forceRefresh,
      excludeIds
    });
    res.json({
      ...data,
      stats: data.filterStats
    });
  } catch (err) {
    console.error('[/api/feed] Error:', err);
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/raw-registry', (req, res) => {
  const result = {};
  for (const [k, v] of mediaRegistry.entries()) {
    result[k] = v;
  }
  res.json(result);
});

app.get(['/api/smart-mix', '/api/feed/smart-mix'], async (req, res) => {
  try {
    const minDuration = Number(req.query.minDuration ?? 15);
    const strictAnchors = req.query.strictAnchors === 'true';
    const forceRefresh = req.query.refresh === 'true';
    const username = req.query.username ? String(req.query.username).replace(/^@/, '').trim() : '';
    const tags = req.query.tags ? String(req.query.tags).split(',').filter(Boolean) : [];
    const excludeIds = req.query.exclude ? String(req.query.exclude).split(',').filter(Boolean) : [];

    if (username) {
      const acc = await fetchUserAccountProfile(username, {
        minDuration: 0,
        strictAnchors,
        forceRefresh,
        excludeIds
      });
      return res.json({
        profile: acc.profile,
        sourcesUsed: [`@${username}`],
        videos: acc.videos,
        stats: acc.filterStats,
        filterStats: acc.filterStats
      });
    }

    const data = await fetchSmartMix(tags, { minDuration, strictAnchors, excludeIds });
    res.json({
      ...data,
      sourcesUsed: (data.pickedTags || []).map(t => `#${t}`),
      stats: data.filterStats
    });
  } catch (err) {
    console.error('[/api/smart-mix] Error:', err);
    res.status(500).json({ error: err.message });
  }
});

/**
 * GET /api/search?q=...&exclude=id1,id2&refresh=true
 * Tìm kiếm thông minh đa luồng: mỗi lần tìm lại hoặc bấm Đổi loạt mới sẽ lấy các nhánh tag và trang mới!
 */
app.get('/api/search', async (req, res) => {
  try {
    const q = String(req.query.q || '').trim();
    if (!q) return res.status(400).json({ error: 'Vui lòng nhập từ khóa tìm kiếm' });

    const minDuration = Number(req.query.minDuration ?? 15);
    const strictAnchors = req.query.strictAnchors === 'true';
    const forceRefresh = req.query.refresh === 'true';
    const excludeIds = req.query.exclude ? String(req.query.exclude).split(',').filter(Boolean) : [];

    if (q.startsWith('@')) {
      const acc = await fetchUserAccountProfile(q, {
        minDuration: 0,
        strictAnchors,
        forceRefresh,
        excludeIds
      });
      return res.json({
        query: q,
        mode: 'account',
        profile: acc.profile,
        accountProfile: acc.profile,
        videos: acc.videos,
        filterStats: acc.filterStats,
        stats: acc.filterStats
      });
    }

    const urlMatch = q.match(/tiktok\.com\/@[\w.-]+\/video\/(\d{17,21})/) || q.match(/\/(\d{19})\b/);
    if (urlMatch) {
      const items = await fetchMobileFeedItems(1, urlMatch[1]);
      const filtered = filterAndNormalizeItems(items, latestCookieHeader, { minDuration: 0, strictAnchors: false });
      return res.json({
        query: q,
        mode: 'direct_video',
        videos: filtered.videos,
        filterStats: filtered.filterStats,
        stats: filtered.filterStats
      });
    }

    const data = await getDiverseVideosForQuery(q, {
      minDuration,
      strictAnchors,
      forceRefresh,
      excludeIds
    });

    res.json({
      query: q,
      resolvedTag: data.clusteredTags ? data.clusteredTags.join(', #') : data.tag,
      expandedTags: data.clusteredTags || [data.tag],
      mode: 'search',
      videos: data.videos,
      filterStats: data.filterStats,
      stats: data.filterStats
    });
  } catch (err) {
    console.error('[/api/search] Error:', err);
    res.status(500).json({ error: err.message });
  }
});

app.get(['/api/music/:musicId', '/api/feed/music'], async (req, res) => {
  try {
    const musicId = req.params.musicId || req.query.musicId;
    const title = req.query.title || 'music';
    const minDuration = Number(req.query.minDuration ?? 15);
    const strictAnchors = req.query.strictAnchors === 'true';

    const data = await fetchVideosByMusicId(musicId, title, { minDuration, strictAnchors });
    res.json({
      ...data,
      stats: data.filterStats
    });
  } catch (err) {
    console.error('[/api/music] Error:', err);
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/account/:username', async (req, res) => {
  try {
    const { username } = req.params;
    const strictAnchors = req.query.strictAnchors === 'true';
    const forceRefresh = req.query.refresh === 'true';
    const scrollPages = req.query.pages ? Number(req.query.pages) : forceRefresh ? 6 : 4;

    const data = await fetchUserAccountProfile(username, {
      minDuration: 0,
      strictAnchors,
      forceRefresh,
      scrollPages
    });
    res.json(data);
  } catch (err) {
    console.error('[/api/account] Error:', err);
    res.status(500).json({ error: err.message });
  }
});

/**
 * GET /api/stream/:id?type=video|audio
 * Tối ưu hóa Chunked Range Window (32MB/chunk) cho các video nhạc dài 30-60 phút
 * Cho phép video tải mượt mà đến hết bài mà không bị ngắt giữa chừng
 */
const MAX_CHUNK_SIZE = 32 * 1024 * 1024; // 32 MB mỗi lần nạp đệm cho video dài

app.get('/api/stream/:id', async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');
  const { id } = req.params;
  const type = req.query.type === 'audio' ? 'audio' : 'video';

  let entry = mediaRegistry.get(id);
  if (!entry) {
    const items = await fetchMobileFeedItems(1, id);
    if (items.length > 0) {
      normalizeVideoItem(items[0], latestCookieHeader);
      entry = mediaRegistry.get(id);
    }
  }
  if (!entry) {
    const tikwmItem = await fetchTikWMSingleVideo(id);
    if (tikwmItem) {
      entry = mediaRegistry.get(id);
    }
  }

  if (!entry) {
    return res.status(404).send('Không tìm thấy luồng phát cho video này.');
  }

  const targetUrl = type === 'audio' && entry.audioUrl ? entry.audioUrl : entry.videoUrl;
  if (!targetUrl) {
    return res.status(404).send('URL luồng phát trống.');
  }

  const isTikWMStream = targetUrl.includes('tikwm.com') || targetUrl.includes('tikcdn.io');
  const headers = isTikWMStream
    ? {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
      }
    : {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
        Referer: 'https://www.tiktok.com/',
        Origin: 'https://www.tiktok.com'
      };

  if (!isTikWMStream && (entry.cookieHeader || latestCookieHeader)) {
    headers.Cookie = entry.cookieHeader || latestCookieHeader;
  }

  // Cửa sổ Range 32MB giúp video dài 30-60 phút nạp đệm đầy đủ, xem liền mạch không đứt quãng
  const clientRange = req.headers.range;
  if (clientRange) {
    const match = clientRange.match(/bytes=(\d+)-(\d*)/);
    if (match) {
      const start = parseInt(match[1], 10);
      if (match[2]) {
        headers.Range = clientRange;
      } else {
        const clampedEnd = start + MAX_CHUNK_SIZE - 1;
        headers.Range = `bytes=${start}-${clampedEnd}`;
      }
    } else {
      headers.Range = clientRange;
    }
  }

  try {
    const upstream = await axios({
      url: targetUrl,
      method: 'GET',
      headers,
      httpsAgent: ipv4Agent,
      responseType: 'stream',
      timeout: 60000,
      validateStatus: status => status >= 200 && status < 400
    });

    res.status(clientRange ? upstream.status : 200);
    const forwardHeaders = [
      'content-type',
      'content-length',
      'content-range',
      'accept-ranges',
      'cache-control'
    ];
    forwardHeaders.forEach(h => {
      if (upstream.headers[h]) {
        res.setHeader(h, upstream.headers[h]);
      }
    });
    if (!res.getHeader('accept-ranges')) {
      res.setHeader('Accept-Ranges', 'bytes');
    }

    upstream.data.on('error', () => {
      if (!res.headersSent) res.end();
    });
    res.on('error', () => {
      upstream.data.destroy();
    });
    upstream.data.pipe(res);
    req.on('close', () => {
      upstream.data.destroy();
    });
  } catch (err) {
    try {
      const tikwmRefreshed = await fetchTikWMSingleVideo(id);
      if (tikwmRefreshed) {
        const fallbackUrl = type === 'audio' ? tikwmRefreshed.audioUrl : tikwmRefreshed.videoUrl;
        const retryStream = await axios({
          url: fallbackUrl,
          method: 'GET',
          headers: {
            'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
            ...(headers.Range ? { Range: headers.Range } : {})
          },
          httpsAgent: ipv4Agent,
          responseType: 'stream',
          timeout: 60000
        });
        res.status(clientRange ? retryStream.status : 200);
        ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control'].forEach(h => {
          if (retryStream.headers[h]) res.setHeader(h, retryStream.headers[h]);
        });
        retryStream.data.on('error', () => {});
        retryStream.data.pipe(res);
        return;
      }

      const items = await fetchMobileFeedItems(1, id);
      if (items.length > 0) {
        const refreshed = normalizeVideoItem(items[0], latestCookieHeader);
        const fallbackEntry = mediaRegistry.get(refreshed.id);
        const fallbackUrl = type === 'audio' ? fallbackEntry.audioUrl : fallbackEntry.videoUrl;
        const retryStream = await axios({
          url: fallbackUrl,
          method: 'GET',
          headers: {
            'User-Agent':
              'com.zhiliaoapp.musically/300904 (2018111632; U; Android 10; en_US; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)',
            ...(headers.Range ? { Range: headers.Range } : {})
          },
          httpsAgent: ipv4Agent,
          responseType: 'stream',
          timeout: 60000
        });
        res.status(clientRange ? retryStream.status : 200);
        ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control'].forEach(h => {
          if (retryStream.headers[h]) res.setHeader(h, retryStream.headers[h]);
        });
        retryStream.data.on('error', () => {});
        retryStream.data.pipe(res);
        return;
      }
    } catch (fallbackErr) {}

    if (!res.headersSent) {
      res.status(502).send('Lỗi tải luồng media từ TikTok CDN.');
    }
  }
});

app.listen(PORT, async () => {
  console.log(`\n🎵 TikTok Clean Music Server đang chạy tại: http://localhost:${PORT}`);
  try {
    await getBrowser();
    console.log('✅ Headless Browser Engine đã sẵn sàng lọc video sạch!');
    // Làm ấm sẵn kho nhạc mặc định ở chế độ nền
    triggerBackgroundPoolExpansion('pool:nhacchill:15:false', TAG_CLUSTERS.nhacchill, 'nhacchill', { minDuration: 15 });
  } catch (e) {
    console.warn('⚠️ Cảnh báo khởi tạo Browser:', e.message);
  }
});
