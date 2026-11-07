const axios = require('axios');

async function test(username) {
  try {
    const res = await axios.get(`https://www.tiktok.com/@${username}`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7'
      },
      timeout: 8000
    });
    console.log('HTML status:', res.status, 'Length:', res.data?.length);
    const m = res.data?.match(/<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>([\s\S]*?)<\/script>/);
    if (m) {
      console.log('Found REHYDRATION! Length:', m[1].length);
      const json = JSON.parse(m[1]);
      const scope = json['__DEFAULT_SCOPE__'];
      const userDetail = scope?.['webapp.user-detail'];
      console.log('User:', userDetail?.userInfo?.user?.nickname, '| id:', userDetail?.userInfo?.user?.id);
      return;
    }
    const sigi = res.data?.match(/<script id="SIGI_STATE"[^>]*>([\s\S]*?)<\/script>/);
    if (sigi) {
      console.log('Found SIGI_STATE!');
      return;
    }
    console.log('No script tag found');
  } catch (err) {
    console.error('Error:', err.message, err.response?.status);
  }
}

test('hbiskibidi');
