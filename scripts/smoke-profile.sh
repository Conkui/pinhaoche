#!/bin/bash
# 拼好车 RFC-011 资料接口冒烟：PUT /users/me（昵称 + 头像落盘）与 /files/** 静态读取。
# 对应 docs/ACCEPTANCE_V1.md 的 E2/E3/E4/E5/E6 接口侧断言（E1/E7 需人工切模式，脚本无法覆盖）。
#
# 前置：docker compose 环境已启动，且后端 WECHAT_MOCK=true（脚本用固定 code 登录，
#       无真实微信依赖）。真实模式下本脚本同样可跑——把登录那步换成真实 code 即可，
#       但真实 code 只能由小程序端 wx.login 产生，故默认面向 mock。
# 用法：bash scripts/smoke-profile.sh
B=http://127.0.0.1:8080/api/v1
O=http://127.0.0.1:8080   # 头像静态资源挂在根路径 /files/**，不带 /api/v1 前缀
cd "$(dirname "$0")"
J() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{try{const o=JSON.parse(d);const v=eval('o'+process.argv[1]);console.log(v===undefined||v===null?'':v)}catch(e){console.log('')}})" "$1"; }
wjson() { printf '%s' "$2" > "q_$1.json"; }
step() { echo; echo "### $1"; }

# 结果计数：所有断言经 ck 记录，末尾统一汇总（与 e2e.sh 的逐条 echo 风格保持一致，
# 额外加计数是因为本脚本用例偏"边界拒绝"，一条 FAIL 很容易被刷屏淹没）
PASS=0
FAIL=0
ck() {
  # $1=用例名 $2=1 表示通过
  if [ "$2" = "1" ]; then echo "PASS $1"; PASS=$((PASS + 1)); else echo "FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# 为什么统一用 node 生成 JSON 文件再 --data-binary @file：
# Git Bash 会对命令行 argv 做编码转换，中文（如昵称）经 -d '...' 传参会变成乱码，
# 服务端收到的是无效字符（历史上踩过），写文件可完全绕开 argv 编码链路。
# 写操作统一携带 Idempotency-Key（AGENTS.md 硬性约束）；PUT /users/me 本身是幂等替换语义，
# 实现若不校验该头也不受影响，故属防御性发送。
wjson sp_nick '{"nickname":"真实昵称测试"}'

# 生成合法 1x1 PNG（RGBA）的 base64：用 zlib + 手写 CRC32 拼真实 PNG 结构，
# 而不是硬编码一段 base64 —— 后者 CRC 一旦抄错，服务端按魔数能过、解码却可能失败，
# 会把"脚本数据错"误报成"接口错"。用真实字节可排除这类假阳性。
node -e "const z=require('zlib');const T=[...Array(256)].map((_,n)=>{let c=n;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;return c>>>0});const crc=b=>{let c=0xffffffff;for(const x of b)c=T[(c^x)&255]^(c>>>8);return (c^0xffffffff)>>>0};const ck=(t,d)=>{const L=Buffer.alloc(4);L.writeUInt32BE(d.length);const td=Buffer.concat([Buffer.from(t),d]);const C=Buffer.alloc(4);C.writeUInt32BE(crc(td));return Buffer.concat([L,td,C])};const ih=Buffer.alloc(13);ih.writeUInt32BE(1,0);ih.writeUInt32BE(1,4);ih[8]=8;ih[9]=6;const raw=Buffer.from([0,255,0,0,255]);const png=Buffer.concat([Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]),ck('IHDR',ih),ck('IDAT',z.deflateSync(raw)),ck('IEND',Buffer.alloc(0))]);require('fs').writeFileSync('q_sp_png.json',JSON.stringify({avatarBase64:png.toString('base64')}))"

# SVG：服务端必须按魔数/内容显式拒绝（SVG 可内嵌脚本，静态直出等于存储型 XSS）
node -e "const svg=Buffer.from(\"<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><script>alert(1)</script></svg>\");require('fs').writeFileSync('q_sp_svg.json',JSON.stringify({avatarBase64:svg.toString('base64')}))"

# 超过 2 MiB 的"图片"：PNG 魔数开头保证它先通过类型判断，专门命中大小上限分支
node -e "const p=Buffer.alloc(2*1024*1024+1024);Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]).copy(p);require('fs').writeFileSync('q_sp_big.json',JSON.stringify({avatarBase64:p.toString('base64')}))"

step "S1 登录（mock 固定 code）"
TA=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"profileSmoke"}' | J .token)
if [ -n "$TA" ]; then echo "token 已获取"; else echo "token 为空：后端是否启动？WECHAT_MOCK 是否为 true？"; fi

step "S2 更新昵称（中文经文件传参）"
C2=$(curl -s -o r_sp_nick.json -w "%{http_code}" -X PUT $B/users/me \
  -H "Authorization: Bearer $TA" -H "Idempotency-Key: sp-nick-$RANDOM$RANDOM" \
  -H 'Content-Type: application/json' --data-binary @q_sp_nick.json)
N2=$(cat r_sp_nick.json | J .nickname)
echo "HTTP=$C2 nickname=$N2"
if [ "$C2" = "200" ] && [ "$N2" = "真实昵称测试" ]; then ck "S2 昵称更新（200 且回显一致）" 1; else ck "S2 昵称更新（HTTP=$C2 nickname=$N2）" 0; fi

step "S3 上传头像（1x1 PNG base64）"
C3=$(curl -s -o r_sp_png.json -w "%{http_code}" -X PUT $B/users/me \
  -H "Authorization: Bearer $TA" -H "Idempotency-Key: sp-png-$RANDOM$RANDOM" \
  -H 'Content-Type: application/json' --data-binary @q_sp_png.json)
AV=$(cat r_sp_png.json | J .avatar)
case "$AV" in /files/*) AVOK=1 ;; *) AVOK=0 ;; esac
echo "HTTP=$C3 avatar=$AV"
if [ "$C3" = "200" ] && [ "$AVOK" = "1" ]; then ck "S3 头像落盘且返回 /files/ 相对路径" 1; else ck "S3 头像落盘（HTTP=$C3 avatar=$AV）" 0; fi

step "S4 读取刚上传的头像（GET /files/**，免鉴权）"
# 头像必须免 token 可读：小程序 <image src> 无法携带 Authorization 头，要求鉴权则头像永远 404
C4=$(curl -s -o /dev/null -w "%{http_code}" "$O$AV")
echo "GET $O$AV → $C4"
if [ "$C4" = "200" ]; then ck "S4 头像可加载（200）" 1; else ck "S4 头像可加载（HTTP=$C4）" 0; fi

step "S5 空对象 → 400（两端字段都缺省）"
C5=$(curl -s -o r_sp_empty.json -w "%{http_code}" -X PUT $B/users/me \
  -H "Authorization: Bearer $TA" -H "Idempotency-Key: sp-empty-$RANDOM$RANDOM" \
  -H 'Content-Type: application/json' -d '{}')
echo "HTTP=$C5"
if [ "$C5" = "400" ]; then ck "S5 空体拒绝（400）" 1; else ck "S5 空体拒绝（HTTP=$C5）" 0; fi

step "S6 SVG → 400（存储型 XSS 面）"
C6=$(curl -s -o r_sp_svg.json -w "%{http_code}" -X PUT $B/users/me \
  -H "Authorization: Bearer $TA" -H "Idempotency-Key: sp-svg-$RANDOM$RANDOM" \
  -H 'Content-Type: application/json' --data-binary @q_sp_svg.json)
echo "HTTP=$C6 body=$(cat r_sp_svg.json)"
if [ "$C6" = "400" ]; then ck "S6 SVG 拒绝（400）" 1; else ck "S6 SVG 拒绝（HTTP=$C6）" 0; fi

step "S7 超 2MiB → 400（大小上限）"
C7=$(curl -s -o r_sp_big.json -w "%{http_code}" -X PUT $B/users/me \
  -H "Authorization: Bearer $TA" -H "Idempotency-Key: sp-big-$RANDOM$RANDOM" \
  -H 'Content-Type: application/json' --data-binary @q_sp_big.json)
echo "HTTP=$C7 body=$(cat r_sp_big.json)"
if [ "$C7" = "400" ]; then ck "S7 超限拒绝（400）" 1; else ck "S7 超限拒绝（HTTP=$C7）" 0; fi

step "S8 不存在的文件 → 404，且目录穿越读不到上传目录之外的文件"
# 为什么补目录穿越断言：单看"不存在 → 404"是弱断言——/files/** 映射整个缺失时它同样成立
# （Spring 默认也会 404）。真正的信号是"映射确实存在（S4=200）+ 越界路径拿不到别的文件"。
C8=$(curl -s -o /dev/null -w "%{http_code}" "$O/files/not-exist-$RANDOM.png")
# --path-as-is：不让 curl 先把 ../ 规范化掉，原样把穿越路径发给服务端。
# 探针选 /files/../../app.jar 而不是 ../application.yml：前者在容器里是**真实存在**的文件
# （Dockerfile 的 WORKDIR=/app 且 jar 就在 /app/app.jar），所以"若资源解析真的被绕过就会拿到它"
# 这个前提成立（application.yml 在 jar 内，路径本就不存在，探了也证明不了什么）。
C8T=$(curl -s --path-as-is -o r_sp_trav.bin -w "%{http_code}" "$O/files/../../app.jar")
LEAK=no
# %PDF/zip 魔数（jar 是 zip：PK\x03\x04）都不该出现；同时排除任何非空二进制响应
if [ -s r_sp_trav.bin ] && [ "$C8T" = "200" ]; then LEAK=yes; fi
echo "不存在文件 HTTP=$C8；穿越路径 /files/../../app.jar HTTP=$C8T 泄露=$LEAK"
if [ "$C8" = "404" ] && [ "$C8T" != "200" ] && [ "$LEAK" = "no" ]; then
  ck "S8 不存在 404 + 目录穿越不可读" 1
else
  ck "S8 不存在 404 + 目录穿越不可读（C8=$C8 C8T=$C8T leak=$LEAK）" 0
fi

# 清理临时文件（2.8MB 的超限载荷留着没意义）；r_sp_*.json 保留会与 e2e.sh 的 r*.json 命名空间一致，
# 但这里统一清掉，避免脚本重复执行后目录里堆满无关 JSON。
rm -f q_sp_nick.json q_sp_png.json q_sp_svg.json q_sp_big.json r_sp_nick.json r_sp_png.json r_sp_empty.json r_sp_svg.json r_sp_big.json r_sp_trav.bin

echo
echo "=============================================="
echo "冒烟汇总：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" = "0" ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL（见上方 FAIL 行；E1/E7 需按 RUNBOOK 人工确认）"; fi
echo "=============================================="
[ "$FAIL" = "0" ] || exit 1
