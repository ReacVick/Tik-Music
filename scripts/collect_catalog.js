const axios = require('axios');
const https = require('https');
const fs = require('fs');
const path = require('path');

const ipv4Agent = new https.Agent({ family: 4, keepAlive: true });
const USER_AGENT = 'com.zhiliaoapp.musically/300904 (2018111632; U; Android 10; en_US; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)';

async function fetchBatch(region = 'VN') {
  const deviceId = '73' + Math.floor(10000000000000000 + Math.random() * 89999999999999999);
  const iid = '73' + Math.floor(10000000000000000 + Math.random() * 89999999999999999);
  const params = new URLSearchParams({
    version_name: '1.1.9',
    version_code: '2018111632',
    carrier_region: region,
    sys_region: region,
    region: region,
    timezone_name: 'Asia/Ho_Chi_Minh',
    channel: 'googleplay',
    ac: 'wifi',
    device_id: deviceId,
    iid: iid,
    _rticket: String(Date.now() * 1000),
    ts: String(Math.floor(Date.now() / 1000))
  });

  try {
    const res = await axios({
      url: 'https://api16-normal-useast5.tiktokv.us/aweme/v1/feed/?' + params.toString(),
      method: 'OPTIONS',
      httpsAgent: ipv4Agent,
      headers: { 'User-Agent': USER_AGENT },
      timeout: 6000
    });
    return res.data?.aweme_list || [];
  } catch (e) {
    return [];
  }
}

function classifyTags(item) {
  const text = ((item.desc || '') + ' ' + (item.music?.title || '') + ' ' + (item.music?.author || '')).toLowerCase();
  const tags = new Set(['nhachaymoingay']);
  
  if (/chill|nhe nhang|thu gian|tam trang|suy|buon|binh yen/.test(text)) {
    tags.add('nhacchill');
  }
  if (/lofi|lo-fi|chill hop|study|sleep/.test(text)) {
    tags.add('lofi');
    tags.add('nhacchill');
  }
  if (/remix|vina|house|bass|nonstop|dj|bay|san|edm/.test(text)) {
    tags.add('remix');
    tags.add('vietmix');
  }
  if (/acoustic|guitar|hat moc|live|cover/.test(text)) {
    tags.add('acoustic');
    tags.add('nhacchill');
  }
  if (/vpop|nhac tre|viet|ballad|pop/.test(text)) {
    tags.add('nhactre');
  }
  if (/piano|khong loi|instrumental|violin/.test(text)) {
    tags.add('pianocover');
    tags.add('healing');
  }
  if (/buon|suy|tam trang|that tinh|cry|sad/.test(text)) {
    tags.add('nhacbuon');
    tags.add('nhacchill');
  }
  if (/bolero|tru tinh|vang|que huong|xua/.test(text)) {
    tags.add('bolero');
  }
  if (/heal|chua lanh|an yen|tinh lang|meditat/.test(text)) {
    tags.add('healing');
  }
  if (/dance|nhay|dance cover|sexy|visual|hot/.test(text)) {
    tags.add('gaixinhnhay');
    tags.add('nhaytrend');
  }
  if (/[a-zA-Z]{5,}/.test(item.music?.title || '') && !/[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]/.test(text)) {
    tags.add('usuk');
  }

  // Luôn đảm bảo ít nhất có 2 tag
  if (tags.size < 2) {
    tags.add('nhacchill');
  }

  return Array.from(tags);
}

async function main() {
  console.log('--- Đang quét kho nhạc sạch TikTok Mobile Feed ---');
  const rawItems = [];
  const regions = ['VN', 'VN', 'VN', 'SG', 'TH', 'VN', 'VN', 'MY', 'VN', 'VN'];

  for (let i = 0; i < regions.length; i++) {
    const batch = await fetchBatch(regions[i]);
    console.log(`Đợt ${i + 1}/${regions.length} (${regions[i]}): ${batch.length} video`);
    rawItems.push(...batch);
  }

  const seenIds = new Set();
  const cleanList = [];

  for (const raw of rawItems) {
    const id = String(raw.aweme_id || raw.id || '');
    if (!id || seenIds.has(id)) continue;
    seenIds.add(id);

    // Lọc bỏ quảng cáo & giỏ hàng
    if (raw.is_ads || raw.is_promoted || raw.commercial_info) continue;
    if (raw.anchors && raw.anchors.some(a => a.type === 24 || a.type === 28)) continue;

    const musicRaw = raw.music || {};
    const videoRaw = raw.video || {};

    const musicUrl = musicRaw.play_url?.url_list?.[0] || '';
    const videoUrl = videoRaw.play_addr?.url_list?.[0] || videoRaw.download_addr?.url_list?.[0] || musicUrl;
    
    if (!videoUrl && !musicUrl) continue;

    const coverUrl = videoRaw.origin_cover?.url_list?.[0] || videoRaw.cover?.url_list?.[0] || musicRaw.cover_large?.url_list?.[0] || '';
    const authorNickname = raw.author?.nickname || 'TikTok Artist';
    const authorUsername = raw.author?.unique_id || raw.author?.short_id || 'tiktok.creator';
    const authorAvatar = raw.author?.avatar_thumb?.url_list?.[0] || raw.author?.avatar_medium?.url_list?.[0] || coverUrl;

    const songTitle = musicRaw.title || raw.desc || 'Giai điệu Thịnh Hành TikTok';
    const songAuthor = musicRaw.author || authorNickname;
    const duration = Math.round(Number(videoRaw.duration || musicRaw.duration || 30000) / (raw.aweme_id ? 1000 : 1)) || 30;

    const hashtags = classifyTags(raw);

    const normalized = {
      id,
      desc: raw.desc || songTitle,
      createTime: raw.create_time || Math.floor(Date.now() / 1000),
      duration,
      width: videoRaw.width || 720,
      height: videoRaw.height || 1280,
      aspectRatio: Number(((videoRaw.width || 720) / (videoRaw.height || 1280)).toFixed(3)),
      isLandscape: (videoRaw.width || 720) > (videoRaw.height || 1280) * 1.05,
      isPinned: false,
      cover: coverUrl,
      streamVideoUrl: videoUrl,
      streamAudioUrl: musicUrl || videoUrl,
      tiktokUrl: `https://www.tiktok.com/@${authorUsername}/video/${id}`,
      author: {
        id: String(raw.author?.uid || id),
        username: authorUsername,
        uniqueId: authorUsername,
        nickname: authorNickname,
        avatar: authorAvatar,
        verified: Boolean(raw.author?.custom_verify)
      },
      music: {
        id: String(musicRaw.id || id),
        title: songTitle,
        author: songAuthor,
        authorName: songAuthor,
        cover: musicRaw.cover_large?.url_list?.[0] || coverUrl,
        duration: musicRaw.duration || duration,
        original: Boolean(musicRaw.is_original_sound),
        hasDirectAudio: Boolean(musicUrl)
      },
      video: {
        duration,
        width: videoRaw.width || 720,
        height: videoRaw.height || 1280,
        aspectRatio: Number(((videoRaw.width || 720) / (videoRaw.height || 1280)).toFixed(3)),
        isLandscape: (videoRaw.width || 720) > (videoRaw.height || 1280) * 1.05,
        cover: coverUrl,
        streamUrl: videoUrl
      },
      stats: {
        plays: raw.statistics?.play_count || 100000,
        diggCount: raw.statistics?.digg_count || 12500,
        commentCount: raw.statistics?.comment_count || 240,
        shareCount: raw.statistics?.share_count || 180,
        collectCount: raw.statistics?.collect_count || 950
      },
      hashtags
    };

    cleanList.push(normalized);
  }

  console.log(`✅ Đã chọn lọc được ${cleanList.length} bài nhạc TikTok sạch chất lượng cao!`);

  // Ghi ra file JSON static để deploy GitHub Pages
  const outPathPublic = path.join(__dirname, '..', 'public', 'feed_data.json');
  const outPathRoot = path.join(__dirname, '..', 'feed_data.json');
  fs.writeFileSync(outPathPublic, JSON.stringify(cleanList, null, 2), 'utf8');
  fs.writeFileSync(outPathRoot, JSON.stringify(cleanList, null, 2), 'utf8');
  console.log('Saved to feed_data.json in public and root');
}

main().catch(console.error);
