// 全局配置：本地联调指向 docker-compose 起的后端；上线替换为 HTTPS 域名并在小程序后台配置白名单。
// 注意：必须保持单文件 config.ts——微信开发者工具的 TS 模块加载不支持目录索引导入
// （require('../config') 只解析 config.js，不回退 config/index.js，曾导致启动即白屏）。
export const BASE_URL = 'http://127.0.0.1:8080/api/v1'
export const WS_URL = 'ws://127.0.0.1:8080/ws/v1/chat'
