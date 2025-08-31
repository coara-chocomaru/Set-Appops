#!/system/bin/sh

set -u
BLACKLIST_PREFIXES="com.google. com.android.vending"
BLACKLIST_EXACT="com.google.android.gms com.google.android.gsf com.google.android.packageinstaller"


total=0
checked=0
skipped_no_permission=0
skipped_blacklist=0
already_allowed=0
set_success=0
set_failed=0
failed_checks=0

log() {
    echo "$@"
}

in_blacklist() {
    pkg="$1"
    
    for b in $BLACKLIST_EXACT; do
        [ "$pkg" = "$b" ] && return 0
    done

    for p in $BLACKLIST_PREFIXES; do
        case "$pkg" in
            "$p"*) return 0 ;;
        esac
    done
    return 1
}

PKG_LIST=$(pm list packages -3 2>/dev/null | cut -d: -f2)

if [ -z "$PKG_LIST" ]; then
    log "警告: 'pm list packages -3' ユーザーアプリ無し）。"
    PKG_LIST=$(pm list packages | cut -d: -f2)
fi

for pkg in $PKG_LIST; do
    total=$((total+1))

    if in_blacklist "$pkg"; then
        skipped_blacklist=$((skipped_blacklist+1))
        log "SKIP (blacklist) : $pkg"
        continue
    fi

    requests_found=1
    if dumpsys package "$pkg" 2>/dev/null | grep -q "REQUEST_INSTALL_PACKAGES"; then
        requests_found=0
    else
        if pm dump "$pkg" 2>/dev/null | grep -q "REQUEST_INSTALL_PACKAGES"; then
            requests_found=0
        else
            requests_found=1
        fi
    fi

    if [ $requests_found -ne 0 ]; then
        skipped_no_permission=$((skipped_no_permission+1))
        log "SKIP (no manifest permission) : $pkg"
        continue
    fi

    checked=$((checked+1))

    appops_out=$(appops get "$pkg" REQUEST_INSTALL_PACKAGES 2>/dev/null || true)
    if [ -z "$appops_out" ]; then
        failed_checks=$((failed_checks+1))
        log "WARN: appops の取得に失敗 : $pkg"
        continue
    fi

    echo "$appops_out" | tr '[:upper:]' '[:lower:]' | grep -qw "allow"
    if [ $? -eq 0 ]; then
        already_allowed=$((already_allowed+1))
        log "ALREADY allowed : $pkg"
        continue
    fi

    log "SET allow : $pkg"
    if appops set "$pkg" REQUEST_INSTALL_PACKAGES allow 2>/dev/null; then
        appops_out2=$(appops get "$pkg" REQUEST_INSTALL_PACKAGES 2>/dev/null || true)
        echo "$appops_out2" | tr '[:upper:]' '[:lower:]' | grep -qw "allow"
        if [ $? -eq 0 ]; then
            set_success=$((set_success+1))
            log "OK : $pkg is now allowed"
        else
            set_failed=$((set_failed+1))
            log "FAIL: set succeeded but verification failed : $pkg"
        fi
    else
        set_failed=$((set_failed+1))
        log "FAIL: appops set failed : $pkg"
    fi
done

echo "-------------------------"
echo "総パッケージ数: $total"
echo "ManifestのREQUEST_INSTALL_PACKAGES存在するか確認: $checked"
echo " - 設定済み: $already_allowed"
echo " - 設定成功: $set_success"
echo " - 設定失敗: $set_failed"
echo "スキップ: ブラックリスト: $skipped_blacklist"
echo "スキップ: Manifestに権限無し: $skipped_no_permission"
echo "警告/確認できない: $failed_checks"
echo "処理完了しましたアプリを終了してください。"
