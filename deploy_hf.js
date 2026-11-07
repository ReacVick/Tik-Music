const fs = require('fs');
const os = require('os');
const path = require('path');
const axios = require('axios');

function loadHfToken() {
  const candidates = [
    path.join(__dirname, '.env'),
    path.join(os.homedir(), '.env')
  ];
  for (const file of candidates) {
    if (fs.existsSync(file)) {
      const content = fs.readFileSync(file, 'utf8');
      const m = content.match(/^HF_TOKEN=(.+)$/m);
      if (m && m[1].trim()) {
        return m[1].trim().replace(/^["']|["']$/g, '');
      }
    }
  }
  return process.env.HF_TOKEN || '';
}

async function deployToHuggingFace() {
  const token = loadHfToken();
  if (!token) {
    console.error('Chưa tìm thấy HF_TOKEN trong file .env');
    process.exit(1);
  }

  const filesToUpload = [
    'README.md',
    'index.html',
    'app.js',
    'styles.css',
    'catalog_bundle.js',
    'feed_data.json',
    'manifest.json'
  ];

  const lines = [];
  lines.push(JSON.stringify({
    key: 'header',
    value: {
      summary: 'Deploy TikTok Music Clean Web App (Ultra-fast multi-cluster + 16:9/9:16 + Dolby Atmos)'
    }
  }));

  for (const fileName of filesToUpload) {
    const publicPath = path.join(__dirname, 'public', fileName);
    const rootPath = path.join(__dirname, fileName);
    let filePath = rootPath;
    if (fs.existsSync(publicPath)) {
      fs.copyFileSync(publicPath, rootPath);
      filePath = publicPath;
    }
    if (!fs.existsSync(filePath)) continue;
    const base64Content = fs.readFileSync(filePath).toString('base64');
    lines.push(JSON.stringify({
      key: 'file',
      value: {
        content: base64Content,
        path: fileName,
        encoding: 'base64'
      }
    }));
  }

  const ndjsonBody = lines.join('\n') + '\n';

  const res = await axios.post(
    'https://huggingface.co/api/spaces/Binh007/Tik-Music/commit/main',
    ndjsonBody,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/x-ndjson'
      },
      maxBodyLength: Infinity,
      maxContentLength: Infinity,
      timeout: 30000
    }
  );

  console.log('✅ Đã deploy thành công lên Hugging Face Space!');
  console.log('📌 Commit URL:', res.data?.commitUrl || 'https://huggingface.co/spaces/Binh007/Tik-Music');
  console.log('🌐 Trang web trực tiếp (Fastly CDN): https://binh007-tik-music.static.hf.space');
  console.log('🌐 Trang Space Hugging Face: https://huggingface.co/spaces/Binh007/Tik-Music');
}

deployToHuggingFace().catch(err => {
  console.error('❌ Lỗi deploy:', err.response?.status, err.response?.data || err.message);
  process.exit(1);
});
