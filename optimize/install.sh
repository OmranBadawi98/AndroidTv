#!/usr/bin/env bash
# Install the two apps on the TV from this computer: urtv-screensaver.apk and urtv-home.apk, your own build, in APK_DIR
# (default optimize/apk; android/build-apks.sh puts them there). APK_URL=https://…/folder downloads them from your own place
# instead. Nothing is taken from the original author's GitHub.
#   optimize/install.sh saver      شاشة تلفزيونك: install + make it the screensaver
#   optimize/install.sh launcher   the home screen: install + make it the default Home
#                                  (it carries the «تلاوة» screens: the Quran recitations and the radio)
#   optimize/install.sh extras     two free apps from their own GitHub releases: TV Bro (a TV web browser,
#                                  github.com/truefedex/tv-bro) and SmartTube (YouTube without ads,
#                                  github.com/yuliskov/SmartTube), the build that fits the TV's processor
#   optimize/install.sh all        both (saver, launcher)
# An APK signed with another key cannot replace the installed one: this script stops with a !! line and never uninstalls
# by itself (that deletes the app's settings); the agent asks the person first.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
APK_DIR="${APK_DIR:-optimize/apk}"
REL="${APK_URL:-}"
what="${1:?usage: optimize/install.sh saver|launcher|extras|all}"
case "$what" in saver|launcher|extras|all) ;; *) echo "usage: optimize/install.sh saver|launcher|extras|all" >&2; exit 2;; esac
. optimize/home-journal.sh   # the journal of the home switch (what the switch changed, to undo it after a restart)
failed=0   # an extra that did not install: the rest are still tried, then run.sh must not count the step as done

# curl with up to 3 tries. A Windows curl (Schannel) that can't reach the certificate revocation server fails every
# download; on that error, and only if this curl has the flag, the next try skips just that check.
curl_get() {
  local i rc=0 errf extra=()
  errf=$(mktemp)
  for i in 1 2 3; do
    rc=0; curl "${extra[@]+"${extra[@]}"}" "$@" 2> "$errf" || rc=$?
    [ "$rc" = 0 ] && { rm -f "$errf"; return 0; }
    [ "$rc" = 22 ] && break   # the server answered with an error (no such file): another try gives the same
    if [ "${#extra[@]}" = 0 ] && grep -qiE 'revocation|CRYPT_E_' "$errf" \
       && curl -V 2> /dev/null | grep -qi schannel && curl --help all 2> /dev/null | grep -q ssl-revoke-best-effort; then
      echo "Windows could not check the certificate revocation list: trying again without that check." >&2
      extra=(--ssl-revoke-best-effort)
    fi
    [ "$i" = 3 ] || sleep $((i * 2))
  done
  cat "$errf" >&2; rm -f "$errf"
  return "$rc"
}

# Play Protect can refuse an ADB install (INSTALL_FAILED_VERIFICATION_FAILURE, seen on a TCL Android 14): its ADB
# check is switched off for that install only, and put back as it was, whatever happens (see the EXIT trap).
VERIFIER=verifier_verify_adb_installs
verifier_old=; verifier_set=0
restore_verifier() {
  [ "$verifier_set" = 1 ] || return 0
  verifier_set=0
  if [ -z "$verifier_old" ] || [ "$verifier_old" = null ]; then
    "$PY" -I optimize/tv.py "settings delete global $VERIFIER" > /dev/null 2>&1 || true
  else
    "$PY" -I optimize/tv.py "settings put global $VERIFIER $verifier_old" > /dev/null 2>&1 || true
  fi
}
trap restore_verifier EXIT

pm_install() {   # $1 file name in /data/local/tmp, $2 package: pm's answer. A big APK can install silently for over a
  # minute, which ADB takes for a dead TV: it runs in the background on the TV and is polled (tv.py --bg).
  # Some operator TVs have a system service that removes apps installed from ADB seconds later; installed with the
  # Play Store as the installer they stay. So: still there after 3 s? If not, install once more that way, and say so.
  # tv.py --bg exits with pm's own exit code (1 on a failed install): that is not a failure of this function. A TV that
  # never answered gives no output at all: NO_ANSWER, which the callers turn into a clear line.
  local out
  out=$(TV_JOB_LIMIT_S=1200 "$PY" -I optimize/tv.py --bg install "pm install -r /data/local/tmp/$1") || true
  if [ -z "$out" ]; then printf '%s\n' NO_ANSWER; return 0; fi
  if grep -q Success <<< "$out"; then
    sleep 3
    if ! "$PY" -I optimize/tv.py "pm list packages $2" | tr -d '\r' | grep -qx "package:$2"; then
      echo "$2 was removed right after the install (a service of this TV does that to apps installed over ADB): installing it again as if from the Play Store." >&2
      out=$(TV_JOB_LIMIT_S=1200 "$PY" -I optimize/tv.py --bg install "pm install -i com.android.vending -r /data/local/tmp/$1") || true
      sleep 3
      if grep -q Success <<< "$out" && ! "$PY" -I optimize/tv.py "pm list packages $2" | tr -d '\r' | grep -qx "package:$2"; then
        out="Failure [$2 is removed by this TV right after every install]"
      fi
    fi
  fi
  printf '%s\n' "$out"
}

push_install() {   # $1 apk name, $2 package
  if [ "$2" = sa.urtv.home ]; then   # the home app targets an older SDK than Play Protect likes (seen on Android 12-14)
    echo "للشخص: بعد لحظات ممكن تطلع على التلفزيون رسالة من Play Protect؛ إذا طلعت اضغط «تثبيت على أي حال» (Install anyway)."
  fi
  if [ -z "$REL" ]; then
    if [ ! -f "$APK_DIR/$1" ]; then
      echo "!! $APK_DIR/$1 is not there: build the apps with your own key first (android/build-apks.sh), then the same command again"; exit 1
    fi
    [ "$APK_DIR/$1" -ef "optimize/$1" ] || cp "$APK_DIR/$1" "optimize/$1"
  elif ! curl_get -fsSL -o "optimize/$1" "$REL/$1"; then
    echo "!! Could not download $REL/$1: no such file, or no internet"; exit 1
  fi
  "$PY" -I optimize/tv.py --push-b64 "optimize/$1" "/data/local/tmp/$1"
  local out
  out=$(pm_install "$1" "$2")
  if grep -q INSTALL_FAILED_VERIFICATION_FAILURE <<< "$out"; then
    verifier_old=$("$PY" -I optimize/tv.py "settings get global $VERIFIER" | tr -d '\r\n' || true)
    echo "Play Protect refused the install over ADB: its ADB check is off for this install only, and put back right after (was: ${verifier_old:-not set})."
    echo "للشخص: لو طلعت على التلفزيون رسالة من Play Protect (التطبيق «غير آمن» أو «مبني لإصدار قديم»)، اضغط «تثبيت على أي حال» (Install anyway) بالريموت. هذا تطبيق تلفزيونك، والرسالة تطلع لأن الشاشة الرئيسية مبنية لإصدار أندرويد أقدم."
    verifier_set=1
    "$PY" -I optimize/tv.py "settings put global $VERIFIER 0" > /dev/null || true
    out=$(pm_install "$1" "$2")
  fi
  if [ "$out" = NO_ANSWER ]; then
    echo "!! The TV stopped answering while $1 was installing (some TVs take minutes and drop ADB meanwhile). Wait a minute, check the TV is on and on Wi-Fi, then run the same command again: an install that finished is found and not repeated."
    exit 1
  fi
  if ! grep -q Success <<< "$out"; then
    # an app with this name but another signature (a copy built by hand, or from another source) cannot be updated in place,
    # and removing it deletes its settings, tasks and own adhan: never done here, the person decides
    if grep -qE 'UPDATE_INCOMPATIBLE|signatures do not match' <<< "$out"; then
      echo "!! $2 is already installed, signed with another key (a copy built by hand or from another source), so ours cannot replace it. Nothing was removed."
      echo "   Removing it (pm uninstall $2) would delete its settings, tasks and own adhan, and Android cannot keep them across signatures. Ask the person first, in Arabic: «عندك نسخة من التطبيق غير الرسمية، لو أزلناها تنحذف إعداداتها ومهامها. أزيلها وأثبّت الرسمية (تتحدّث وحدها بعدين)؟». Only after a yes: pm uninstall $2, then the same command again."
      exit 1
    else
      echo "!! Install of $1 failed: $out"
      if grep -q INSTALL_FAILED_VERIFICATION_FAILURE <<< "$out"; then
        echo "   Play Protect still refuses it. Tell the person, in Arabic: «اضغط على التلفزيون «تثبيت على أي حال» لما تطلع رسالة Play Protect، وإذا ما طلعت: الإعدادات ← متجر Play ← Play Protect ← «فحص التطبيقات» وقف مؤقتاً، وبعدها نعيد المحاولة». Do not turn the verifier off yourself (settings put global package_verifier_enable 0): only this script changes verifier_verify_adb_installs, for the install, and puts it back."
      fi
      exit 1
    fi
  fi
  restore_verifier
  "$PY" -I optimize/tv.py "rm -f /data/local/tmp/$1"
}

from_github() {   # $1 owner/repo, $2 package, $3 asset pattern ({abi} = the TV's processor types, best first)
  local abis url name out code body size
  # an extra that an earlier try already installed is not downloaded or uploaded again: a flaky Wi-Fi that dropped on the second
  # one resumes there, not from the first (an upload cut off half way is resumed by tv.py itself)
  if "$PY" -I optimize/tv.py "pm list packages $2" | tr -d '\r' | grep -qx "package:$2"; then
    echo "$2 is already on the TV: left as it is (it updates itself)."; return 0
  fi
  abis=$("$PY" -I optimize/tv.py "getprop ro.product.cpu.abilist" | tr -d '\r')
  body=$(curl_get -sSL -w '\n%{http_code}' "https://api.github.com/repos/$1/releases/latest") || body=$'\n000'
  code=${body##*$'\n'}; body=${body%$'\n'*}
  if [ "$code" = 403 ] || [ "$code" = 429 ]; then
    echo "!! GitHub rate limit, try later: $1 skipped"; failed=1; return 0
  elif [ "$code" != 200 ]; then
    echo "!! GitHub did not list the releases of $1 (HTTP $code): skipped"; failed=1; return 0
  fi
  url=$("$PY" -I -c '
import json, re, sys
assets = {a["name"]: (a["browser_download_url"], a.get("size", 0)) for a in json.load(sys.stdin)["assets"]}
for abi in [a for a in sys.argv[2].split(",") if a] + ["universal", ""]:
    pat = re.compile(sys.argv[1].replace("{abi}", re.escape(abi)) + "$")
    hit = sorted(n for n in assets if pat.search(n))
    if hit:
        print(assets[hit[0]][0], assets[hit[0]][1]); break
' "$3" "$abis" <<< "$body") || true
  if [ -z "$url" ]; then echo "!! No build of $1 for this TV ($abis): skipped"; return 0; fi
  size=${url##* }; url=${url% *}
  name=$(basename "$url")
  if [ -f "optimize/$name" ] && [ "$size" != 0 ] && [ "$(wc -c < "optimize/$name" | tr -d ' ')" = "$size" ]; then
    echo "$name is already downloaded: not downloading it again."
  elif ! curl_get -fsSL -o "optimize/$name" "$url"; then echo "!! Could not download $url"; failed=1; return 0; fi
  "$PY" -I optimize/tv.py --push-b64 "optimize/$name" "/data/local/tmp/$name"
  out=$(pm_install "$name" "$2")
  "$PY" -I optimize/tv.py "rm -f /data/local/tmp/$name" > /dev/null 2>&1 || true
  rm -f "optimize/$name"
  if [ "$out" = NO_ANSWER ]; then
    # an extra never stops the setup: the install may even have finished while the TV was silent
    if "$PY" -I optimize/tv.py "pm list packages $2" 2> /dev/null | tr -d '\r' | grep -qx "package:$2"; then echo "Installed $name (the TV was slow to answer)"
    else echo "!! $2 was skipped: the TV stopped answering while it installed (slow Wi-Fi or a slow set). The rest of the setup goes on; to add it later: optimize/install.sh extras"; fi
  elif grep -q Success <<< "$out"; then echo "Installed $name"
  elif grep -qE 'UPDATE_INCOMPATIBLE|signatures do not match' <<< "$out"; then
    echo "$2 is already installed from somewhere else: left as it is (with its settings)."   # never removed
  else echo "!! Install of $name failed: $out"; failed=1; fi
}

# A home switch that a restart cut off (journal says «switching», Home is not ours, no marker) is undone first.
[ "$what" = extras ] || jrecover || exit 1

if [ "$what" = extras ]; then
  from_github truefedex/tv-bro com.phlox.tvwebbrowser 'tvbro-[0-9.]+-generic-geckoExcluded\.apk'   # light: the TV's WebView
  from_github yuliskov/SmartTube org.smarttube.stable 'SmartTube_stable_[0-9.]+_{abi}\.apk'
  [ "$failed" = 0 ] || exit 1
fi

if [ "$what" = saver ] || [ "$what" = all ]; then
  push_install urtv-screensaver.apk sa.urtv.screensaver
  # a WebView older than 80 cannot run the page: the TV keeps its own screensaver (ours is installed, not selected) until
  # the person updates «Android System WebView»; precheck.sh held exits 4 then, and says nothing when it cannot tell
  held=0; PY="$PY" optimize/precheck.sh held > /dev/null 2>&1 || { [ "$?" != 4 ] || held=1; }
  if [ "$held" = 1 ]; then
    echo "!! The screensaver is installed but NOT switched on: this TV's WebView is older than 80 and the page would not run, so the TV keeps its own screensaver. Update «Android System WebView» in the Play Store (optimize/precheck.sh webview-store opens it), then run optimize/install.sh saver again."
  else
    printf '%s\n' \
      "settings put secure screensaver_enabled 1" \
      "settings put secure screensaver_components sa.urtv.screensaver/.ScreenDream" \
      "settings put secure screensaver_default_component sa.urtv.screensaver/.ScreenDream" \
      "am start --activity-clear-task -n sa.urtv.screensaver/.SettingsActivity" \
      | "$PY" -I optimize/tv.py --batch
    # read back: some firmware keeps its own screensaver whatever is written (reset.sh puts back what backup.sh recorded)
    got=$("$PY" -I optimize/tv.py "settings get secure screensaver_components" 2> /dev/null | tr -d '\r' | tail -n 1) || got=""
    case "$got" in
      *sa.urtv.screensaver/*) ;;
      *) echo "!! screensaver_components: this TV did not take our screensaver (it says ${got:-nothing}); choose it in the TV's own settings (Settings, Device Preferences, Screen saver)" ;;
    esac
    echo "Screensaver installed and selected. Its settings screen is open on the TV."
  fi
fi

if [ "$what" = launcher ] || [ "$what" = all ]; then
  # Which stock home does this TV have? Google TV sets: launcherx. Older Android TV: tvlauncher.
  if "$PY" -I optimize/tv.py "pm list packages com.google.android.apps.tv.launcherx" | grep -q launcherx; then
    STOCK=com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity
  else
    STOCK=com.google.android.tvlauncher/.MainActivity
  fi
  echo "Stock home: $STOCK"

  # 1. The new home must be installed and working BEFORE anything is disabled.
  push_install urtv-home.apk sa.urtv.home
  "$PY" -I optimize/tv.py "pm grant sa.urtv.home android.permission.ACCESS_FINE_LOCATION" || true
  # exact alarms for the adhan (Android 12 and newer; older TVs need nothing)
  "$PY" -I optimize/tv.py "appops set sa.urtv.home SCHEDULE_EXACT_ALARM allow" || true

  # The setup wizard (setupwraith) is turned off only when it really answers Home. Disabling it can delete the Wi-Fi network
  # that was saved during the first-run wizard (CleanYourTV's README, github.com/Gathin23/cleanyourtv, MIT: a network saved
  # then belongs to the wizard, and Android removes an app's saved networks when it is disabled; two TCL sets lost their
  # Wi-Fi password at the first restart, and a Nokia refused it). Their guard asks `dumpsys wifi` who owns the networks.
  WRAITH=com.google.android.tungsten.setupwraith
  wraith_answers_home() {   # the apps that answer HOME, all of them (resolve-activity only names the winner)
    "$PY" -I optimize/tv.py "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2> /dev/null; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" \
      | grep "$WRAITH" > /dev/null   # no grep -q: with pipefail its early exit would SIGPIPE the command before it
  }
  wifi_owner() {   # owned: a saved network belongs to the wizard; other: none does; unknown: the TV did not say
    local w owners
    w=$("$PY" -I optimize/tv.py "dumpsys wifi") || { echo unknown; return; }
    owners=$(grep -o 'cname=[^ ]*' <<< "$w" || true)
    if [ -z "$owners" ]; then echo unknown
    elif grep -q setupwraith <<< "$owners"; then echo owned
    else echo other; fi
  }
  wraith_off() {   # 0: turned off; 1: left on (owns the Wi-Fi, or it could not be told)
    case "$(wifi_owner)" in
      other) "$PY" -I optimize/tv.py "pm disable-user --user 0 $WRAITH" > /dev/null || true; return 0 ;;
      owned) echo "The setup wizard of this TV owns its saved Wi-Fi network: left on, so the network is not lost." ;;
      *)     echo "Could not tell who owns the saved Wi-Fi network: the setup wizard is left on." ;;
    esac
    return 1
  }

  # Steps 2-4 as one function, so an ADB drop in the middle (TCL G08: Wi-Fi fell during the batch) can be retried.
  # "verify" as $1: only step 4 (the switch is armed already and the batch may have run).
  # Returns 0: the new home is on screen and the switch is disarmed; 1: tv.py failed (connection lost);
  # 2: Home did not open the new launcher; 3: the safety switch fired first.
  switch_home() {
    local top res stepout ours=no _ wraith=no cmds alive disabled
    if [ "${1:-}" != verify ]; then
      # before anything is changed: does the wizard answer Home, and can it go without taking the Wi-Fi with it?
      if wraith_answers_home; then
        # owned: the switch goes on with the wizard left on (the HOME role and set-home-activity choose ours, and the
        # check below and the safety switch catch a TV where it still wins). Stopping here blocked 13 of 39 setups on
        # 51b9680, and forgetting the network did not help on half of them.
        case "$(wifi_owner)" in
          owned) echo "The setup wizard answers Home and owns the saved Wi-Fi network: it is left on (the network stays), and the switch goes on without turning it off." ;;
          other) wraith=listed ;;
          *) echo "The setup wizard answers Home, but the TV did not say who owns its saved Wi-Fi: the wizard is left on, so the network is not lost." ;;
        esac
      fi
      # 2. Arm the safety switch on the TV: it restores the stock home in 300 s unless we confirm (slow ADB needs time).
      #    It carries a nonce (a marker of an older switch cannot disarm this one), and it must show it is alive before
      #    anything is changed. The journal records what the switch changes, to undo it if the TV restarts in the middle.
      local old_nonce role wiz stk
      old_nonce=$(jget nonce)
      NONCE="$(date +%s)-$$-$RANDOM"
      role=$("$PY" -I optimize/tv.py "cmd role get-role-holders --user 0 android.app.role.HOME 2> /dev/null" 2> /dev/null | tr -d '\r' | sed -n 1p) || role=""
      grep -qE '^[A-Za-z0-9_.]+$' <<< "$role" || role=""
      # a second run starts with ours as the holder: the earlier journal still knows who it was before
      if [ "$role" = sa.urtv.home ] || [ -z "$role" ]; then role=$(jget role_holder_before); fi
      wiz=absent; stk=absent
      if disabled=$("$PY" -I optimize/tv.py "pm list packages -d" 2> /dev/null | tr -d '\r'); then
        grep -qx "package:$WRAITH" <<< "$disabled" && wiz=disabled || wiz=enabled
        grep -qx "package:${STOCK%%/*}" <<< "$disabled" && stk=disabled || stk=enabled
      fi
      [ "$wiz" != disabled ] || [ "$(jget wizard_before)" != enabled ] || wiz=enabled   # we turned it off in an earlier run
      [ "$stk" != disabled ] || [ "$(jget stock_before)" != enabled ] || stk=enabled
      jwrite "$NONCE" switching "$STOCK" "$role" "$wiz" "$stk"
      "$PY" -I optimize/tv.py --push-b64 optimize/deadman.sh /data/local/tmp/deadman.sh || return 1
      printf '%s\n' \
        "${old_nonce:+touch /data/local/tmp/home_ok.$old_nonce}" \
        "rm -f /data/local/tmp/home_ok /data/local/tmp/home_ok.$NONCE" \
        "for f in /data/local/tmp/home_ok.* /data/local/tmp/home_pid.* /data/local/tmp/home_hb.*; do case \$f in *.$NONCE|*.${old_nonce:-none}|*'*') ;; *) rm -f \$f ;; esac; done" \
        "if command -v setsid > /dev/null 2>&1; then setsid sh /data/local/tmp/deadman.sh $STOCK 300 $NONCE < /dev/null > /dev/null 2>&1 & else nohup sh /data/local/tmp/deadman.sh $STOCK 300 $NONCE < /dev/null > /dev/null 2>&1 & fi" \
        | grep . | "$PY" -I optimize/tv.py --batch || return 1
      # alive = its pid answers and its heartbeat is under 25 s old (it writes one every 10 s)
      alive=no
      for _ in 1 2 3 4 5; do
        sleep 2
        res=$("$PY" -I optimize/tv.py "p=\$(cat /data/local/tmp/home_pid.$NONCE 2> /dev/null); h=\$(cat /data/local/tmp/home_hb.$NONCE 2> /dev/null); if [ -n \"\$p\" ] && kill -0 \$p 2> /dev/null && [ -n \"\$h\" ] && [ \$((\$(date +%s) - h)) -le 25 ]; then echo WATCHDOG_ALIVE; else echo WATCHDOG_DEAD; fi") || return 1
        if grep -q WATCHDOG_ALIVE <<< "$res"; then alive=yes; break; fi
      done
      if [ "$alive" = yes ]; then
        echo "Safety switch armed and running: the stock home comes back by itself in 5 minutes unless the new home works."
      else
        # Seen on a TCL with Android 14: a background job started over ADB ends the moment the ADB session closes (setsid and nohup
        # did not help), so there is no safety switch on the TV itself. The switch is still checked and undone from this computer
        # (below), and the journal restores it on the next run if this computer stops in the middle. Not an error: it is the
        # protection every earlier install had.
        jsafety unavailable
        echo "This TV ends background jobs when the ADB session closes, so there is no safety switch on the TV itself. The switch is checked and undone from this computer, and the journal restores it on the next run."
      fi

      # 3. Switch: the HOME role (Android 10+; the classic set-home-activity for older sets and as a backup), then
      #    the setup app off, then the stock home (this order avoided the black screen).
      cmds=("cmd role add-role-holder --user 0 android.app.role.HOME sa.urtv.home"
            "cmd package set-home-activity --user 0 sa.urtv.home/.MainActivity")
      [ "$wraith" != listed ] || cmds+=("pm disable-user --user 0 $WRAITH")
      cmds+=("pm disable-user --user 0 ${STOCK%%/*}"
             "cmd package set-home-activity --user 0 sa.urtv.home/.MainActivity"
             "am force-stop com.android.tv.settings"
             "input keyevent KEYCODE_HOME")
      stepout=$(printf '%s\n' "${cmds[@]}" | "$PY" -I optimize/tv.py --batch) || return 1
      printf '%s\n' "$stepout"
      # Nokia Streaming Box 8000 answers «This command is illegal» to pm disable-user for its own launcher: no switch there.
      # Put the stock home back as it was and stop the switch (4); nothing else of the setup depends on it.
      if grep -A2 -F "\$ pm disable-user --user 0 ${STOCK%%/*}" <<< "$stepout" | grep -qi 'illegal'; then
        printf '%s\n' "cmd role add-role-holder --user 0 android.app.role.HOME ${STOCK%%/*}" \
          "cmd package set-home-activity --user 0 $STOCK" "pm enable com.google.android.tungsten.setupwraith" \
          "touch /data/local/tmp/home_ok.$NONCE" "input keyevent KEYCODE_HOME" | "$PY" -I optimize/tv.py --batch > /dev/null \
          && jstate restored   # only when it went through: else the journal stays «switching» and the next run redoes it
        return 4
      fi
    fi
    # 4. Verify Home really opens the new launcher (a few tries: a slow TV), then disarm the switch.
    for _ in 1 2 3; do
      sleep 6
      top=$("$PY" -I optimize/tv.py "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'") || return 1
      if grep -q sa.urtv.home <<< "$top"; then ours=yes; break; fi
      "$PY" -I optimize/tv.py "input keyevent KEYCODE_HOME" > /dev/null || return 1
      # it took Home back after the switch: now it has to go (only if that cannot cost the Wi-Fi)
      if [ "$wraith" = no ] && wraith_answers_home; then wraith=listed; wraith_off || true; fi
    done
    if [ "$ours" != yes ]; then
      # the stock home back now, not in 5 minutes: a restart in between would stop the safety switch and could leave the
      # wizard's recovery screen as Home (seen on a TCL G07 with the stock home off and the wizard on)
      printf '%s\n' "pm enable ${STOCK%%/*}" "pm enable $WRAITH" \
        "cmd role add-role-holder --user 0 android.app.role.HOME ${STOCK%%/*}" "cmd package set-home-activity --user 0 $STOCK" \
        "touch /data/local/tmp/home_ok.$NONCE" "input keyevent KEYCODE_HOME" | "$PY" -I optimize/tv.py --batch > /dev/null \
        && jstate restored   # (see above: not «restored» if the batch did not go through)
      return 2
    fi
    "$PY" -I optimize/tv.py "touch /data/local/tmp/home_ok.$NONCE" || return 1
    # the switch may have fired already if ADB was very slow: then the stock home is back
    res=$("$PY" -I optimize/tv.py "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN") || return 1
    grep -q sa.urtv.home <<< "$res" || { jstate restored; return 3; }   # the safety switch fired: the stock home is back
    jstate done
  }


  # Home is already ours and no switch was cut off: there is nothing to switch and nothing to protect (no safety switch, no journal)
  already=no
  res=$("$PY" -I optimize/tv.py "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" 2> /dev/null) || res=""
  if grep -q sa.urtv.home <<< "$res" && [ "$(jget state)" != switching ]; then
    already=yes
    echo "Home is already the new launcher: nothing to switch, so no safety switch is started."
  fi
  rc=0; if [ "$already" = yes ]; then :; else switch_home || rc=$?; fi
  if [ "$rc" = 1 ]; then   # ADB dropped: wait for the TV, see what Home is now, and go on or start the switch again
    back=no
    for _ in $(seq 36); do   # every 10 s, about 6 minutes
      if "$PY" -I optimize/tv.py "getprop ro.serialno" > /dev/null 2>&1; then back=yes; break; fi
      sleep 10
    done
    if [ "$back" = no ]; then
      echo "!! The TV did not come back within 6 minutes. Check it is on and on Wi-Fi: the safety switch restores the stock home."
      echo "   Then run the same command again."
      exit 1
    fi
    res=$("$PY" -I optimize/tv.py "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" 2> /dev/null) || res=""
    rc=0
    if grep -q sa.urtv.home <<< "$res"; then
      echo "ADB dropped during the switch; the TV is back and the new home is already set: checking it"
      switch_home verify || rc=$?
    else
      echo "ADB dropped during the switch; the TV is back, trying the switch again"
      switch_home || rc=$?   # arms the switch again first
    fi
  fi
  home_skipped=0
  case "$rc" in
    0) ;;
    4) home_skipped=1
       echo "Home switch skipped: this TV refuses to turn its own home screen off («This command is illegal», seen on a Nokia Streaming Box 8000). It keeps its own home; the stock home is back as it was. The screensaver and the rest of the setup work without it."
       mkdir -p "${URTV_RUN_DIR:-optimize/run}"
       echo "Home switch: skipped, this TV refuses to turn its own home screen off" > "${URTV_RUN_DIR:-optimize/run}/home-skipped.txt" ;;
    2) echo "!! Home did not open the new launcher. The stock home was put back."
       echo "   Some TCL firmware pins Home to the stock launcher; on those sets keep the stock home."
       echo "   If the line above said the setup wizard owns the Wi-Fi, keep the stock home too: never turn the wizard off by hand."
       exit 1 ;;
    *) echo "!! The safety switch put the stock home back (ADB was too slow). Nothing is broken: run the same command again."
       exit 1 ;;
  esac
  if [ "$home_skipped" = 0 ]; then
    # 5. Only now: the stock home's helpers are dead weight (restore.sh brings them back).
    grep -vE '^\s*(#|$)' optimize/lists/home-replaced.txt | awk '{print "pm disable-user --user 0 " $1}' \
      | "$PY" -I optimize/tv.py --batch > /dev/null || true
    rm -f "${URTV_RUN_DIR:-optimize/run}/home-skipped.txt"
    echo "Launcher is home. Press Home from inside an app to double-check."
  fi
  echo "Undo everything: optimize/reset.sh"
fi

# The report's «Versions:» line was written by run.sh before this install: say what is on the TV now, so run.sh's report
# and share.sh agree. (Only when the report exists; no report, nothing to correct.)
if [ "$what" != extras ] && [ -f "${URTV_RUN_DIR:-optimize/run}/report.md" ]; then
  rep="${URTV_RUN_DIR:-optimize/run}/report.md"
  vs=$("$PY" -I optimize/tv.py "dumpsys package sa.urtv.screensaver 2> /dev/null | grep versionName | sed -n 1p" 2> /dev/null | sed 's/.*=//' | tr -d '\r ') || vs=""
  vh=$("$PY" -I optimize/tv.py "dumpsys package sa.urtv.home 2> /dev/null | grep versionName | sed -n 1p" 2> /dev/null | sed 's/.*=//' | tr -d '\r ') || vh=""
  if [ -n "$vs$vh" ]; then
    line="Versions: screensaver ${vs:-none}, home ${vh:-none}"
    if grep -q '^Versions:' "$rep"; then
      awk -v l="$line" '/^Versions:/ { print l; next } { print }' "$rep" > "$rep.tmp" && mv "$rep.tmp" "$rep"
    else
      printf '%s\n' "$line" >> "$rep"
    fi
  fi
fi
