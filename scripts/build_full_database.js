const fs = require('fs');
const path = require('path');

async function run() {
  console.log('=== BẮT ĐẦU TỔNG HỢP KHO NHẠC TIKTOK SẠCH ===');

  // 1. Đọc dữ liệu từ feed_data.json hiện tại (từ mobile feed)
  let baseItems = [];
  try {
    const raw = fs.readFileSync(path.join(__dirname, '..', 'feed_data.json'), 'utf8');
    baseItems = JSON.parse(raw);
    console.log(`Đã nạp ${baseItems.length} bài từ mobile feed.`);
  } catch (e) {
    console.warn('Chưa có feed_data.json:', e.message);
  }

  // 2. Lấy dữ liệu từ localhost server (mediaRegistry & tag feeds)
  const tagsToFetch = [
    'nhacchill', 'lofi', 'remix', 'acoustic', 'nhactre',
    'vietmix', 'bolero', 'healing', 'pianocover', 'nhacbuon'
  ];

  let registry = {};
  try {
    const regRes = await fetch('http://localhost:3000/api/raw-registry');
    registry = await regRes.json();
    console.log(`Đã nạp ${Object.keys(registry).length} mục từ mediaRegistry.`);
  } catch (e) {
    console.warn('Không thể kết nối registry:', e.message);
  }

  const scrapedItems = [];
  for (const tag of tagsToFetch) {
    try {
      const res = await fetch(`http://localhost:3000/api/feed?tag=${tag}`);
      const data = await res.json();
      const list = data.videos || [];
      console.log(`Tag #${tag}: ${list.length} video.`);
      for (const v of list) {
        const regEntry = registry[v.id];
        const directAudio = regEntry?.audioUrl || regEntry?.videoUrl || v.streamAudioUrl;
        const directVideo = regEntry?.videoUrl || directAudio;
        if (!directAudio) continue;

        // Gán hashtag chính xác
        const currentTags = Array.isArray(v.hashtags) ? [...v.hashtags] : [];
        if (!currentTags.includes(tag)) currentTags.push(tag);
        if (!currentTags.includes('nhachaymoingay')) currentTags.push('nhachaymoingay');

        const cleanItem = {
          id: String(v.id),
          desc: v.desc || v.music?.title || 'Bài hát TikTok',
          createTime: v.createTime || Math.floor(Date.now() / 1000),
          duration: v.duration || 30,
          width: v.width || 720,
          height: v.height || 1280,
          aspectRatio: v.aspectRatio || 0.563,
          isLandscape: Boolean(v.isLandscape),
          isPinned: false,
          cover: v.cover || regEntry?.coverUrl || v.music?.cover || '',
          streamVideoUrl: directVideo,
          streamAudioUrl: directAudio,
          tiktokUrl: v.tiktokUrl || `https://www.tiktok.com/@${v.author?.uniqueId || 'creator'}/video/${v.id}`,
          author: {
            id: String(v.author?.id || v.id),
            username: v.author?.username || v.author?.uniqueId || 'creator',
            uniqueId: v.author?.uniqueId || v.author?.username || 'creator',
            nickname: v.author?.nickname || 'TikTok Artist',
            avatar: v.author?.avatar || v.cover || '',
            verified: Boolean(v.author?.verified)
          },
          music: {
            id: String(v.music?.id || v.id),
            title: v.music?.title || v.desc || 'Giai điệu Thịnh Hành',
            author: v.music?.author || v.author?.nickname || 'TikTok Artist',
            authorName: v.music?.authorName || v.author?.nickname || 'TikTok Artist',
            cover: v.music?.cover || v.cover || '',
            duration: v.music?.duration || v.duration || 30,
            original: Boolean(v.music?.original),
            hasDirectAudio: true
          },
          video: {
            duration: v.video?.duration || v.duration || 30,
            width: v.video?.width || 720,
            height: v.video?.height || 1280,
            aspectRatio: v.video?.aspectRatio || 0.563,
            isLandscape: Boolean(v.video?.isLandscape),
            cover: v.video?.cover || v.cover || '',
            streamUrl: directAudio // Audio stream plays transparently in HTML5 video element with 206!
          },
          stats: v.stats || {
            plays: 120000,
            diggCount: 15000,
            commentCount: 300,
            shareCount: 200,
            collectCount: 1200
          },
          hashtags: currentTags
        };
        scrapedItems.push(cleanItem);
      }
    } catch (err) {
      console.warn(`Lỗi tag #${tag}:`, err.message);
    }
  }

  // 3. Hợp nhất và loại bỏ trùng lặp
  const mergedMap = new Map();
  for (const item of [...scrapedItems, ...baseItems]) {
    if (!item.id || !item.streamAudioUrl) continue;
    if (!mergedMap.has(item.id)) {
      mergedMap.set(item.id, item);
    } else {
      // Gộp hashtags
      const existing = mergedMap.get(item.id);
      const combinedTags = new Set([...(existing.hashtags || []), ...(item.hashtags || [])]);
      existing.hashtags = Array.from(combinedTags);
    }
  }

  const finalCatalog = Array.from(mergedMap.values());
  console.log(`\n🎉 Tổng số bài nhạc sạch chất lượng cao: ${finalCatalog.length} bài!`);

  // Phân bố theo hashtag
  const stats = {};
  finalCatalog.forEach(item => {
    (item.hashtags || []).forEach(h => {
      stats[h] = (stats[h] || 0) + 1;
    });
  });
  console.log('Thống kê theo thể loại:', stats);

  // 4. Lưu ra các file
  const rootDir = path.join(__dirname, '..');
  const publicDir = path.join(rootDir, 'public');
  const androidAssetDir = path.join(rootDir, 'android', 'app', 'src', 'main', 'assets');

  const jsonStr = JSON.stringify(finalCatalog, null, 2);

  fs.writeFileSync(path.join(publicDir, 'feed_data.json'), jsonStr, 'utf8');
  fs.writeFileSync(path.join(rootDir, 'feed_data.json'), jsonStr, 'utf8');
  if (fs.existsSync(androidAssetDir)) {
    fs.writeFileSync(path.join(androidAssetDir, 'feed_data.json'), jsonStr, 'utf8');
  }

  // Tạo catalog_bundle.js để nhúng trực tiếp vào HTML
  const bundleContent = `// Tự động sinh - Kho bài nhạc sạch chuẩn TikTok Clean
window.__INITIAL_FEED_CATALOG__ = ${JSON.stringify(finalCatalog)};
console.log('Loaded bundled TikTok Music catalog: ' + window.__INITIAL_FEED_CATALOG__.length + ' tracks.');
`;
  fs.writeFileSync(path.join(publicDir, 'catalog_bundle.js'), bundleContent, 'utf8');
  fs.writeFileSync(path.join(rootDir, 'catalog_bundle.js'), bundleContent, 'utf8');
  if (fs.existsSync(androidAssetDir)) {
    fs.writeFileSync(path.join(androidAssetDir, 'catalog_bundle.js'), bundleContent, 'utf8');
  }

  console.log('✅ Đã xuất feed_data.json và catalog_bundle.js thành công!');
}

run().catch(console.error);
