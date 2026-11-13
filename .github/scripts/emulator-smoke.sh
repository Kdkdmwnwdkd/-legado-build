#!/usr/bin/env bash
# 模拟器冒烟测试。由 .github/workflows/emulator-smoke.yml 调用。
#
# 这段逻辑必须放在文件里而不是 workflow 的 script 参数里：
# android-emulator-runner 会把 script 逐行交给 `sh -c` 执行，
# 每行一个独立 shell，`APK=$(...)` 的赋值传不到下一行，
# 结果是 `adb install -r ""` 报 "filename doesn't end .apk"。

set +e

# debug 变体在 applicationId 上加了 .debug 后缀；Activity 的完整类名则由
# namespace（com.shimmer.reader）决定，不是 applicationId —— 所以不能用
# "$PKG/.ui.main.MainActivity" 这种相对写法，必须向系统要真实组件名。
PKG=com.shimmer.reader.debug

APK=$(find app/build/outputs/apk -name '*.apk' | head -n 1)
echo "APK=$APK"
if [ -z "$APK" ]; then
  echo "::error::没有找到 APK"
  exit 0
fi

echo "::group::install"
adb install -r "$APK"
echo "install exit=$?"
echo "::endgroup::"

echo "::group::device"
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell wm density
echo "::endgroup::"

echo "::group::launch"
adb shell pm list packages | grep -i shimmer || echo '(没有找到 shimmer 包)'
ACT=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tail -n 1 | tr -d '\r')
echo "resolved component: [$ACT]"
case "$ACT" in
  */*) ;;
  *) ACT="$PKG/com.shimmer.reader.ui.main.MainActivity"; echo "回退到: $ACT" ;;
esac
adb logcat -c
adb shell am start -W -n "$ACT"
echo "start exit=$?"
echo "::endgroup::"

sleep 25

shot() {
  adb shell screencap -p "/sdcard/$1.png"
  adb pull "/sdcard/$1.png" "$1.png"
}

shot shot-1-launch

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}
H=${SIZE#*x}
echo "screen=${W}x${H}"

echo "::group::state"
adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -3
echo "pid: $(adb shell pidof $PKG)"
echo "::endgroup::"

# 首次启动会弹隐私协议，AGREE 在卡片右下角（1080x2400 下实测约 88%, 63%）。
# 点不中也不会造成误操作——那个位置除了按钮没有别的东西。
echo "::group::dismiss-onboarding"
# 第一步：隐私协议 AGREE（右下角）
adb shell input tap $((W * 88 / 100)) $((H * 63 / 100))
sleep 4
# 第二步：首次启动会紧接着弹「帮助文档」，右下角 Close。
# 若某一轮没有弹，这次点击落在空白处，无副作用。
adb shell input tap $((W * 55 / 100)) $((H * 83 / 100))
sleep 4
# 第三步：本地密码设置对话框，点 CANCEL（备份加密是可选功能，这里只测主流程）
adb shell input tap $((W * 66 / 100)) $((H * 63 / 100))
sleep 4
shot shot-2-main
echo "pid after dismiss: $(adb shell pidof $PKG)"
echo "::endgroup::"

# 底部导航 6 个 tab 的中心位置（按 1080x2400 实测换算，两侧有 padding，
# 不是严格均分）
TAB_Y=$((H * 94 / 100))
tap_tab() {
  local pct="$1" name="$2"
  adb shell input tap $((W * pct / 100)) "$TAB_Y"
  sleep 5
  shot "$name"
  echo "pid after tab ${pct}%: $(adb shell pidof $PKG)"
}

echo "::group::switch-tabs"
# 顺序有讲究：搜索页会弹软键盘，键盘会盖住底部导航，
# 所以搜索放在最后，避免影响后续点击。
tap_tab 73  shot-3-mine
tap_tab 29  shot-4-discovery
tap_tab 14  shot-5-bookshelf
tap_tab 88  shot-6-search
echo "::endgroup::"

# 默认语言是简体中文，所以上面跑出来的是中文界面。
# 这一段把偏好里的 language 改成 en 重启一次，用来检查英文资源有没有漏翻或串味。
# debug 包 debuggable=true，可以直接用 run-as 改私有目录里的偏好文件。
echo "::group::switch-to-english"
PREFS="shared_prefs/${PKG}_preferences.xml"
adb shell am force-stop "$PKG"
sleep 2
adb shell "run-as $PKG cat $PREFS" > pref.xml 2>/dev/null
if [ -s pref.xml ]; then
  python3 - <<'PY'
import re
s = open('pref.xml', encoding='utf-8').read()
if '<string name="language">' in s:
    s = re.sub(r'<string name="language">[^<]*</string>',
               '<string name="language">en</string>', s)
else:
    s = s.replace('<map>', '<map>\n    <string name="language">en</string>', 1)
open('pref.xml', 'w', encoding='utf-8').write(s)
PY
  adb push pref.xml /data/local/tmp/pref.xml >/dev/null
  adb shell "run-as $PKG sh -c 'cat /data/local/tmp/pref.xml > $PREFS'"
  adb shell am start -W -n "$ACT" >/dev/null
  sleep 12
  tap_tab 73  shot-7-en-mine
  tap_tab 14  shot-8-en-bookshelf
  tap_tab 29  shot-9-en-discovery
else
  echo "::warning::没有读到偏好文件，跳过英文界面检查"
fi
echo "::endgroup::"

adb logcat -d -v threadtime > logcat.txt

echo "::group::crash scan"
grep -nE 'FATAL EXCEPTION|UnsatisfiedLinkError|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError' logcat.txt \
  | head -40 || echo '没有匹配到崩溃关键字'
# ANR 单独查，且只认本包——模拟器里的 dialer 等系统应用经常会自己 ANR
grep -nE "ANR in .*shimmer" logcat.txt | head -10 || echo '本应用没有 ANR'
echo "::endgroup::"

echo "::group::app log"
grep -iE "$PKG|legado|shimmer|AndroidRuntime" logcat.txt | tail -50
echo "::endgroup::"
