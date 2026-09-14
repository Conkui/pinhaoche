#!/bin/bash
# 拼好车 V1 验收 B 清单模拟调用（需 docker compose 环境已启动）
B=http://127.0.0.1:8080/api/v1
ADMIN_KEY="${ADMIN_KEY:?请从 .env 导出 ADMIN_KEY 后再运行验收脚本}"
cd "$(dirname "$0")"
J() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{try{const o=JSON.parse(d);const v=eval('o'+process.argv[1]);console.log(v===undefined||v===null?'':v)}catch(e){console.log('')}})" "$1"; }
wjson() { printf '%s' "$2" > "q_$1.json"; }
step() { echo; echo "### $1"; }

step "B1 登录"
TA=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userA"}' | J .token)
ID_A=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userA"}' | J .user.id)
TB=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userB"}' | J .token); ID_B=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userB"}' | J .user.id)
TC=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userC"}' | J .token)
TD=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userD"}' | J .token)
ID_B2=$(curl -s $B/auth/login -H 'Content-Type: application/json' -d '{"code":"userB"}' | J .user.id)
echo "A=$ID_A B=$ID_B2"
if [ -n "$TA" ] && [ "$ID_A" != "$ID_B2" ]; then echo "B1 PASS"; else echo "B1 FAIL"; fi

step "B2 预设地点"
wjson e1 '{"name":"学校东门","sortOrder":1}'; wjson e2 '{"name":"高铁站","sortOrder":2}'
curl -s -o /dev/null -X POST $B/admin/places -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' --data-binary @q_e1.json
curl -s -o /dev/null -X POST $B/admin/places -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' --data-binary @q_e2.json
PL=$(curl -s $B/places -H "Authorization: Bearer $TA"); PID=$(echo "$PL" | J "[0].id")
if [ -n "$PID" ]; then echo "B2 PASS pid=$PID"; else echo "B2 FAIL: $PL"; fi

step "B3/B4 发布+幂等重放"
DEPART=$(node -e "console.log(new Date(Date.now()+86400000).toISOString())")
KEY1=pub-$RANDOM$RANDOM
wjson r3 "{\"origin\":{\"type\":\"PRESET\",\"placeId\":$PID,\"text\":\"学校东门\"},\"destination\":{\"type\":\"CUSTOM\",\"text\":\"万达广场2号门\"},\"departTime\":\"$DEPART\",\"memberCount\":1,\"capacity\":4,\"remark\":\"行李多\"}"
R3=$(curl -s $B/rides -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: $KEY1" -H 'Content-Type: application/json' --data-binary @q_r3.json)
RID=$(echo "$R3" | J .id)
R4=$(curl -s $B/rides -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: $KEY1" -H 'Content-Type: application/json' --data-binary @q_r3.json)
if [ -n "$RID" ] && [ "$(echo "$R3" | J .origin.text)" = "学校东门" ] && [ "$(echo "$R4" | J .id)" = "$RID" ]; then
  echo "B3/B4 PASS ride=$RID 快照=学校东门"; else echo "B3/B4 FAIL r3=$R3"; fi

step "B5/B6 加入与重复加入"
R5=$(curl -s $B/rides/$RID/join -X POST -H "Authorization: Bearer $TB" -H "Idempotency-Key: jb1-$RANDOM$RANDOM")
C6=$(curl -s -o /dev/null -w "%{http_code}" $B/rides/$RID/join -X POST -H "Authorization: Bearer $TB" -H "Idempotency-Key: jb2-$RANDOM$RANDOM")
if [ "$(echo "$R5" | J .memberCount)" = "2" ] && [ "$(echo "$R5" | J .status)" = "MATCHING" ] && [ "$C6" = "400" ]; then
  echo "B5/B6 PASS"; else echo "B5/B6 FAIL mc=$(echo "$R5" | J .memberCount) st=$(echo "$R5" | J .status) c6=$C6"; fi

step "B7 并发抢最后一位（cap=2，3人并发）"
DEPART2=$(node -e "console.log(new Date(Date.now()+86400000).toISOString())")
wjson r7 "{\"origin\":{\"type\":\"PRESET\",\"placeId\":$PID,\"text\":\"学校东门\"},\"destination\":{\"type\":\"CUSTOM\",\"text\":\"机场\"},\"departTime\":\"$DEPART2\",\"memberCount\":1,\"capacity\":2}"
R7=$(curl -s $B/rides -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: pub7-$RANDOM$RANDOM" -H 'Content-Type: application/json' --data-binary @q_r7.json)
RID7=$(echo "$R7" | J .id)
KEYB=ccb-$RANDOM$RANDOM; KEYC=ccc-$RANDOM$RANDOM; KEYD=ccd-$RANDOM$RANDOM
curl -s -o r_b.json -w "%{http_code}" $B/rides/$RID7/join -X POST -H "Authorization: Bearer $TB" -H "Idempotency-Key: $KEYB" > code_b.txt &
curl -s -o r_c.json -w "%{http_code}" $B/rides/$RID7/join -X POST -H "Authorization: Bearer $TC" -H "Idempotency-Key: $KEYC" > code_c.txt &
curl -s -o r_d.json -w "%{http_code}" $B/rides/$RID7/join -X POST -H "Authorization: Bearer $TD" -H "Idempotency-Key: $KEYD" > code_d.txt &
wait
RB=$(cat code_b.txt); RC=$(cat code_c.txt); RD=$(cat code_d.txt)
echo "B=$RB C=$RC D=$RD（应恰好一个 200，其余 409）"
FINAL=$(curl -s $B/rides/$RID7 -H "Authorization: Bearer $TA" | J .memberCount)
OK7=$(node -e "const a=['$RB','$RC','$RD'];console.log(a.filter(x=>x==='200').length===1&&a.every(x=>['200','409'].includes(x))?'PASS':'FAIL')")
echo "B7 $OK7 最终memberCount=$FINAL"

step "B8 会话复用"
S1=$(curl -s $B/chat-sessions -X POST -H "Authorization: Bearer $TB" -H 'Content-Type: application/json' -d "{\"rideId\":$RID,\"peerUserId\":$ID_A}")
SID=$(echo "$S1" | J .id)
S2=$(curl -s $B/chat-sessions -X POST -H "Authorization: Bearer $TB" -H 'Content-Type: application/json' -d "{\"rideId\":$RID,\"peerUserId\":$ID_A}")
if [ -n "$SID" ] && [ "$(echo "$S2" | J .id)" = "$SID" ]; then echo "B8 PASS sid=$SID"; else echo "B8 FAIL s1=$S1"; fi

step "B10 补偿拉取（空增量）"
M0=$(curl -s "$B/chat-sessions/$SID/messages?afterId=0" -H "Authorization: Bearer $TB")
if [ "$M0" = "[]" ]; then echo "B10 PASS"; else echo "B10 FAIL $M0"; fi

step "B11 导入已打上"
wjson r11 "{\"provider\":\"DIDI\",\"plateNumber\":\"苏A12345\",\"origin\":{\"type\":\"PRESET\",\"placeId\":$PID,\"text\":\"学校东门\"},\"destination\":{\"type\":\"CUSTOM\",\"text\":\"高铁站\"},\"remainingDistance\":18.6,\"waitingMinutes\":10,\"seats\":2}"
R11=$(curl -s $B/rides/import -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: imp-$RANDOM$RANDOM" -H 'Content-Type: application/json' --data-binary @q_r11.json)
BID=$(echo "$R11" | J .id)
if [ "$(echo "$R11" | J .status)" = "BOOKED" ] && [ "$(echo "$R11" | J .plateNumber)" = "苏A12345" ]; then
  echo "B11 PASS booked=$BID"; else echo "B11 FAIL r11=$R11"; fi

step "B12/B13 非法迁移/越权"
CV=$(curl -s $B/rides/$RID -H "Authorization: Bearer $TA" | J .version)
C12=$(curl -s -o /dev/null -w "%{http_code}" $B/rides/$RID/transitions -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: tx1-$RANDOM$RANDOM" -H 'Content-Type: application/json' -d "{\"targetStatus\":\"ARRIVED\",\"version\":$CV}")
C13=$(curl -s -o /dev/null -w "%{http_code}" $B/rides/$RID/transitions -X POST -H "Authorization: Bearer $TB" -H "Idempotency-Key: tx2-$RANDOM$RANDOM" -H 'Content-Type: application/json' -d '{"targetStatus":"CANCELLED","version":999}')
if [ "$C12" = "409" ] && [ "$C13" = "403" ]; then echo "B12/B13 PASS"; else echo "B12/B13 FAIL $C12/$C13"; fi

step "B14 不再等人 BOOKED→IN_PROGRESS"
BV=$(curl -s $B/rides/$BID -H "Authorization: Bearer $TA" | J .version)
CODE14=$(curl -s -o r14.json -w "%{http_code}" $B/rides/$BID/transitions -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: ta1-$RANDOM$RANDOM" -H 'Content-Type: application/json' -d "{\"targetStatus\":\"IN_PROGRESS\",\"version\":$BV}")
N=$(curl -s "$B/rides?view=BOOKED" -H "Authorization: Bearer $TB" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).length))")
if [ "$CODE14" = "200" ] && [ "$N" = "0" ]; then echo "B14 PASS"; else echo "B14 FAIL code=$CODE14 body=$(cat r14.json) bookedLeft=$N"; fi

step "B15 时间效益红线：过期单不展示（60s 后落 EXPIRED）"
PAST=$(node -e "console.log(new Date(Date.now()-3600000).toISOString())")
wjson r15 "{\"origin\":{\"type\":\"PRESET\",\"placeId\":$PID,\"text\":\"学校东门\"},\"destination\":{\"type\":\"CUSTOM\",\"text\":\"汽车站\"},\"departTime\":\"$PAST\",\"memberCount\":1,\"capacity\":4}"
R15=$(curl -s $B/rides -X POST -H "Authorization: Bearer $TA" -H "Idempotency-Key: pub15-$RANDOM$RANDOM" -H 'Content-Type: application/json' --data-binary @q_r15.json)
RID15=$(echo "$R15" | J .id)
HAS=$(curl -s "$B/rides?view=PREBOOK" -H "Authorization: Bearer $TB" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).some(r=>r.id===$RID15)?'yes':'no'))")
if [ "$HAS" = "no" ]; then echo "B15(展示过滤) PASS rid15=$RID15"; else echo "B15 FAIL rid15=$RID15"; fi
echo "$RID15" > .rid15

step "B16 反馈"
C16a=$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/feedback -H 'Content-Type: application/json' -d '{"content":"x"}')
wjson f1 '{"content":"希望增加校友群功能"}'
C16b=$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/feedback -H "Authorization: Bearer $TA" -H 'Content-Type: application/json' --data-binary @q_f1.json)
if [ "$C16a" = "401" ] && [ "$C16b" = "204" ]; then echo "B16 PASS"; else echo "B16 FAIL $C16a/$C16b"; fi
