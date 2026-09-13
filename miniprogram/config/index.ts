// 全局配置：本地联调指向 docker-compose 起的后端；上线替换为 HTTPS 域名并在小程序后台配置白名单。
export const BASE_URL = 'http://127.0.0.1:8080/api/v1'
export const WS_URL = 'ws://127.0.0.1:8080/ws/v1/chat'
